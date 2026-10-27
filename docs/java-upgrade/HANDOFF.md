# Handoff - continuar em F1 a F5

Atualizado em 2026-10-27 apos o fechamento da F0. Plano completo: `E8-framework-plan.md`. Historico: `PLAN.md`.

## Estado
- F0 concluida (ver "F0 - fechamento" no plano). Proxima etapa: **F1**.
- Branch `pilot/java8-to-21`, arvore limpa, sem push. Runtime e build em Java 21; bytecode 1.8 (Boot 2.3.12 / Hoxton.SR12).
- JDK de trabalho: `export JAVA_HOME=$HOME/.cache/codex-java-upgrade/temurin21-21.0.13+9/jdk-21.0.13+9; export PATH=$JAVA_HOME/bin:$PATH`.
- Gates: `mvn -B -ntp clean verify` (exit 0); `python3 /mnt/d/Projects/agent-marketplace/plugins/java-development/skills/java-upgrade/scripts/compare_surefire.py <baseline> <depois>`; `python3 tools/check_bytecode.py <major>`.
- Baseline de identidades: XMLs do E4g em `/mnt/c/Users/felip/Documents/Codex/2026-10-22/que/outputs/piggymetrics-jdk11-linux/run-20261025T030740Z-156543/test-reports.tar.gz` (110 testes); a F0 acrescentou 3 (113).
- Stack local: `docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d` usa imagens Java 21 construidas localmente (tags `sqshq/piggymetrics-*`). A API externa de cambio (exchangeratesapi.io) devolve 401: falha externa conhecida.

## Decisoes do usuario
D1 pontes F1 a F5 (escada de bytecode 8 -> 11 -> 17 -> 21). D2 Actuator + Micrometer + Prometheus + Grafana no lugar de Hystrix Dashboard/Turbine. D3 atualizar e substituir o necessario (Flapdoodle -> Testcontainers; `mongo:3` em etapa propria F4m, com restauracao do dump e retorno ensaiado). D4 login por authorization code com redirect aceito. D5 normalizado para LF (feito).

## Skill
Repo `/mnt/d/Projects/agent-marketplace`, skill `java-development:java-upgrade`, etapa `references/framework-major.md`. A skill sera preparada para o proximo LTS (Java 25) depois que a aplicacao estiver com runtime e bytecode em 21. O `AGENTS.md` do repo exige dois pareceres sequenciais de subagentes para mudancas de comportamento da skill; os commits recentes dela nao os tiveram.

## Prompt para a proxima sessao
```
Reinicie ciente do contexto: leia docs/java-upgrade/HANDOFF.md e docs/java-upgrade/E8-framework-plan.md em /mnt/d/Projects/piggymetrics e use a skill java-development:java-upgrade (etapa framework-major) para executar F1 a F5, uma etapa por vez. Antes de editar cada etapa registre entrada, matriz, fontes oficiais lidas, gates e retorno no plano. F1 primeiro: Ribbon -> Spring Cloud LoadBalancer e Hystrix -> Spring Cloud CircuitBreaker (Resilience4j) ainda em Boot 2.3/Hoxton, confirmando na fonte oficial que Hoxton suporta ambos; comece caracterizando Bus/RabbitMQ e metricas. Mantenha os testes existentes, rode compare_surefire.py e tools/check_bytecode.py como gates, nao use skipTests nem enfraqueca asserts. Os testes de balanceamento por nome sao contrato atual do Ribbon: se o novo comportamento for diferente, registre a mudanca deliberada. Commit por etapa so quando os gates passarem.
```
