# Handoff - F0 a F5 concluidas

Atualizado em 2026-10-28 apos o fechamento da F5. Relatorio: `FINAL-REPORT.md`. Plano: `E8-framework-plan.md`. Historico: `PLAN.md`.

## Estado
- F0 a F5 concluidas (tags f0-final ... f4-final; F5 so documentacao). Branch `pilot/java8-to-21`, sem push.
- Alvo: Boot 4.1.2 / Cloud 2025.1.4, bytecode 21 (major 65), Mongo 8.0, 119 testes verdes.
- JDK de trabalho: `export JAVA_HOME=$HOME/.cache/codex-java-upgrade/temurin21-21.0.13+9/jdk-21.0.13+9; export PATH=$JAVA_HOME/bin:$PATH`.
- Gates: `mvn -B -ntp clean verify`; `compare_surefire.py <antes> <depois>`; `python3 tools/check_bytecode.py 65`.
- Stack: `docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d` (imagens `:latest` = `:f4`; `:f3`/`:f4a` guardadas). Espere o primeiro cadastro dar 200 antes de rodar fluxos.
- Pendencias abertas: ver "Pendencias finais" no plano e `FINAL-REPORT.md` (navegador real, API de cambio, JRE por digest, Java 25).

## Decisoes do usuario
D1 pontes F1 a F5 (escada de bytecode 8 -> 11 -> 17 -> 21). D2 Actuator + Micrometer + Prometheus + Grafana no lugar de Hystrix Dashboard/Turbine. D3 atualizar e substituir o necessario (Flapdoodle -> Testcontainers; `mongo:3` em etapa propria F4m, com restauracao do dump e retorno ensaiado). D4 login por authorization code com redirect aceito. D5 normalizado para LF (feito).

## Skill
Repo `/mnt/d/Projects/agent-marketplace`, skill `java-development:java-upgrade`, etapa `references/framework-major.md`. A skill sera preparada para o proximo LTS (Java 25) depois que a aplicacao estiver com runtime e bytecode em 21. O `AGENTS.md` do repo exige dois pareceres sequenciais de subagentes para mudancas de comportamento da skill; os commits recentes dela nao os tiveram.
