## Summary

Restore the Gateway Nacos integration tests as a reliable release gate.

- Declare the fixed API and gRPC ports as exposed Testcontainer ports so the HTTP wait strategy can address them.
- Bound the Nacos container heap and the Surefire JVM memory used by Gateway tests.
- Run each Gateway test class in an isolated bounded JVM to prevent Nacos, Reactor, and Spring static state from leaking across tests.
- Record the resource-boundary retro in `docs/learning/31-gateway-nacos-test-resource-boundaries.md`.

Closes #158

## TDD evidence

The original test setup was RED before business assertions because Testcontainers reported
`Target port 28848 is not exposed`. After correcting the port declaration, the default JVM
hit a native OOM. Once the container and test JVM were bounded, the end-to-end discovery
assertion passed; a later shared-JVM full run exposed an order-dependent Nacos startup
failure, which was reproduced as a container timeout while the isolated test still passed.

## Verification

| Check | Result |
| --- | --- |
| `GatewayNacosDiscoveryIntegrationTest` | 2/2 passed |
| Isolated Surefire full suite, first run | 11/11 passed, 44.7 s |
| Isolated Surefire full suite, repeat | 11/11 passed, 45.3 s |
| Final integration tree full suite | 11/11 passed, 46.8 s |
| Nacos container heap | 512 MiB max |
| Surefire JVM heap | 768 MiB max |
| `git diff --check` | passed |

Nacos, gRPC, gateway registration, and the real `lb://kuros-backend` forwarding path are
covered by live Docker tests. The fixed test ports prevent parallel execution of multiple
Gateway test JVMs.

## Manual CI

[CI run 39735808203](https://github.com/qingshuixiaohang/kuros/actions/runs/39735808203)
passed all five jobs on head `feeea00519a7b577f3889aef1acdffe0c90bd699`.
