#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
project="smp-practice5-$$"
temp_dir=$(mktemp -d)
compose_file="$temp_dir/compose.json"
compose=(docker compose -p "$project" -f "$compose_file")

cleanup() {
    result=$?
    if [[ -s "$compose_file" ]]; then
        if [[ "$result" -ne 0 ]]; then
            "${compose[@]}" logs --tail=50 >&2 || true
        fi
        "${compose[@]}" down --volumes --remove-orphans >&2 || true
    fi
    rm -f "$compose_file" "$temp_dir/cards-unreachable.json" "$temp_dir/switch-unreachable.json" \
        "$temp_dir/rate-limit.json" "$temp_dir/card-stub.json"
    rmdir "$temp_dir"
}
trap cleanup EXIT

# Preserve production Compose wiring while giving the test stack its own
# containers, network, database volume and dynamically allocated loopback ports.
docker compose --env-file .env.example config --format json | jq '
    .services |= with_entries(select(.key | IN(
        "postgres", "rabbitmq", "gateway", "switch", "authorization",
        "card-management", "transaction-logger", "terminal-simulator",
        "merchant-acquirer", "bin-lookup"))) |
    del(.name) |
    .networks |= with_entries(.value |= del(.name)) |
    .volumes |= with_entries(.value |= del(.name)) |
    .services |= with_entries(
        .key as $name |
        .value |= (
            del(.container_name, .ports) |
            if has("build") then
                .image = ("smp-practice5/" + $name + ":test") |
                .ports = [{"target": 8080, "host_ip": "127.0.0.1", "protocol": "tcp"}]
            else . end
        )
    )
' > "$compose_file"

if [[ "${API_TEST_SKIP_BUILD:-0}" != 1 ]]; then
    "${compose[@]}" build
fi
"${compose[@]}" up -d --no-build --wait --wait-timeout 180

run_tests() {
    local group="$1"
    shift
    "${MAVEN:-mvn}" -B -ntp -f services/pom.xml -Papi-tests \
        "-Dapi.test.groups=$group" \
        "-Dapi.gateway.url=http://$("${compose[@]}" port gateway 8080)" \
        "-Dapi.cards.url=http://${cards_address}" \
        "-Dapi.switch.url=http://${switch_address}" \
        "-Dapi.authorization.url=http://$("${compose[@]}" port authorization 8080)" \
        "-Dapi.logger.url=http://$("${compose[@]}" port transaction-logger 8080)" \
        "-Dapi.merchant.url=http://$("${compose[@]}" port merchant-acquirer 8080)" \
        "-Dapi.terminal.url=http://$("${compose[@]}" port terminal-simulator 8080)" \
        "-Dapi.bin.url=http://$("${compose[@]}" port bin-lookup 8080)" "$@" test
}

switch_address=$("${compose[@]}" port switch 8080)
cards_address=$("${compose[@]}" port card-management 8080)
repeats=${API_TEST_REPEATS:-1}
for ((run = 1; run <= repeats; run++)); do
    run_tests contract "$@"
done

# Stop the RabbitMQ application while retaining its container and DNS entry.
"${compose[@]}" exec -T rabbitmq rabbitmqctl stop_app
run_tests broker-down "$@"
"${compose[@]}" exec -T rabbitmq rabbitmqctl start_app

# Keep DNS resolvable and use a closed port to reproduce connection refusal.
# /actuator/health is process readiness while /health is the contract under test.
jq -n '{services:{authorization:{
    environment:{CARD_MGMT_URL:"http://transaction-logger:1"},
    healthcheck:{test:["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health"]}
}}}' > "$temp_dir/cards-unreachable.json"
"${compose[@]}" -f "$temp_dir/cards-unreachable.json" up -d --no-build \
    --no-deps --wait --wait-timeout 120 authorization
run_tests cards-down "$@"
"${compose[@]}" up -d --no-build --no-deps --wait --wait-timeout 120 authorization

jq -n '{services:{gateway:{
    environment:{SWITCH_URL:"http://127.0.0.1:18999"},
    healthcheck:{test:["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health"]}
}}}' > "$temp_dir/switch-unreachable.json"
"${compose[@]}" -f "$temp_dir/switch-unreachable.json" up -d --no-build \
    --no-deps --wait --wait-timeout 120 gateway
run_tests switch-down "$@"

jq -n '{services:{gateway:{environment:{
    TRANSACTIONS_RATE_LIMIT_CAPACITY:"1",
    TRANSACTIONS_RATE_LIMIT_REFILL_PER_SECOND:"1"
}}}}' > "$temp_dir/rate-limit.json"
"${compose[@]}" -f "$temp_dir/rate-limit.json" up -d --no-build \
    --no-deps --wait --wait-timeout 120 gateway
run_tests rate-limit "$@"

jq -n --arg fixture "$PWD/services/api-tests/target/test-classes" '{services:{
    authorization:{environment:{CARD_MGMT_URL:"http://card-management-stub:8080"}},
    "card-management-stub":{
        image:"smp-practice5/authorization:test",
        entrypoint:["java", "-cp", "/fixture", "com.processing.api.support.CardManagementStub"],
        networks:["smp-network"],
        volumes:[{type:"bind",source:$fixture,target:"/fixture",read_only:true}],
        healthcheck:{test:["CMD", "wget", "-qO-", "http://localhost:8080/health"],
            interval:"2s",timeout:"2s",retries:10}
    }
}}' > "$temp_dir/card-stub.json"
compose+=(-f "$temp_dir/card-stub.json")
"${compose[@]}" up -d --no-build --no-deps --wait --wait-timeout 45 card-management-stub
"${compose[@]}" up -d --no-build --no-deps --wait --wait-timeout 120 authorization
run_tests card-stub "$@"
