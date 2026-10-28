# Relatorio final - piggymetrics Java 8 para 21 (F0 a F5) e Java 25 (F6)

Data: 2026-10-28. Branch `pilot/java8-to-21`, sem push. Detalhes por etapa em `E8-framework-plan.md`.

## Origem e destino
| | Origem | Destino |
| --- | --- | --- |
| JDK build | Java 8 (E4g) | Temurin 21.0.13 |
| Bytecode | 1.8 | 21 (major 65) |
| Runtime das imagens | Java 8 | `eclipse-temurin:21-jre-jammy` (21.0.12.1 observado) |
| Spring Boot / Cloud | 1.x/2.3 (Hoxton) | 4.1.2 / 2025.1.4 |
| MongoDB | `mongo:3` | `mongo:8.0` (8.0.34) |
| Plataforma | Linux amd64 (WSL2 + Docker) | idem |

## Mudancas por etapa
F0 caracterizacao e gate de bytecode; F1 Ribbon/Hystrix -> LoadBalancer/CircuitBreaker (Resilience4j); F1b Hystrix Dashboard/Turbine -> Prometheus/Grafana; F2 Boot 2.7.18, Zuul -> Spring Cloud Gateway; F3 Boot 3.5.16, jakarta, Micrometer Tracing, Spring Authorization Server (authorization code + PKCE, introspeccao RFC 7662); F4a Boot 4.1.2/Cloud 2025.1.4, starters modulares, `spring.mongodb.*`, Testcontainers 2; F4m Mongo 8.0, `mongosh`, dump com `replaceOne`.
Commits: f2-final 0351a85, f3-final b6c02e1, f4a ea0fcd5, f4-final f6cabc7.

## Evidencia
- Build: `mvn -B -ntp clean verify` verde no reactor completo, sem `skipTests`; `tools/check_bytecode.py 65` ok.
- Testes: baseline original 110; final 119. `compare_surefire.py`: removed=5 added=14 not_passed=0 (explicados no plano, F5).
- Fluxos vivos: cadastro, login code+PKCE, token, autorizacao por escopo (200/401/403), PUT account, notifications, rotas do gateway, Eureka, Prometheus, Grafana, traces, dump `demo`.
- Retorno ensaiado `:f4` -> `:f3` (Mongo 4.4) -> `:f4` (Mongo 8.0), volumes novos.

## Mudancas deliberadas de comportamento
Password grant removido (browser usa authorization code + PKCE; sem refresh token para o cliente publico); `check_token`/user-info -> introspeccao; tokens em memoria (restart do auth os invalida); sem charset em `application/json`; `@Email` do Jakarta; cliente de token OAuth sem repeticao automatica; Hystrix Dashboard/Turbine removidos.

## Falhas e riscos
- Externa e preexistente: `exchangeratesapi.io` exige chave (estatisticas vazias, fallback do account loga erro).
- Dados entre Mongo 4.4 e 8.0 nao migram; volumes antigos foram descartados (ambiente de desenvolvimento).
- Login em navegador real, dashboard Grafana visual e rollback com rebuild por tag git nao validados.
- Tag JRE flutuante; Security 7 migration guide e notas Cloud por projeto nao lidos (cobertos por falhas reais).
- Spring Cloud 2025.1.4 declara Boot 4.0.x; compatibilidade com 4.1.2 vem da pagina do projeto e dos testes.

## Proximos passos
Validar em navegador; fixar a imagem JRE por digest; decidir sobre a chave/substituto da API de cambio; preparar a skill para Java 25 (Boot 4.1 declara suporte ate 27); revisar o tratamento dos poms/legacy listados no plano. Nao ha autorizacao para deploy em ambiente compartilhado.

## Adendo F6 - Java 25 (2026-10-28)
Piloto em GraalVM CE 25.0.2 (build e imagens `ghcr.io/graalvm/jdk-community:25.0.2`), bytecode 25 (major 69), JaCoCo 0.8.11 -> 0.8.14 (unica dependencia alterada; o 0.8.11 falhava em silencio nas classes do JDK). Commits 4227b2a, 40b0bed, e5c9ff6, tag `f6b-final`, sem push. Evidencia: build verde sem `skipTests`, `check_bytecode.py 69`, 119/119 testes sem removidos (`compare_surefire.py` vs `after7`), fluxos vivos de F5, retorno ensaiado para `:f4` (JRE 21). Riscos: aviso JEP 472 do Netty, imagem por tag e nao digest, login proprio do usuario fora da evidencia. A skill `java-upgrade` ganhou a linha 21 -> 25 e as licoes F0-F5 na branch `feat/java-upgrade-jdk25-lessons` do `agent-marketplace`, sem publicacao.
