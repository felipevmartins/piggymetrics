# Handoff - F0 a F6 concluidas

Atualizado em 2026-10-28 apos o fechamento da F5. Relatorio: `FINAL-REPORT.md`. Plano: `E8-framework-plan.md`. Historico: `PLAN.md`.

## Estado
- F0 a F6 concluidas (tags f0-final ... f4-final, f6b-final; F5 so documentacao; F6 = skill + piloto Java 25). Branch `pilot/java8-to-21`, sem push.
- Alvo: Boot 4.1.2 / Cloud 2025.1.4, Mongo 8.0, 119 testes verdes. Java 25: bytecode 25 (major 69), JDK de build e imagens GraalVM CE 25.0.2 (`ghcr.io/graalvm/jdk-community:25.0.2`), JaCoCo 0.8.14. Ate F5: bytecode 21 (tag f5-final, imagens `:f4`).
- JDK de trabalho (Java 25): `export JAVA_HOME=$HOME/.cache/codex-java-upgrade/graalvm-ce-25.0.2/graalvm-community-openjdk-25.0.2+10.1; export PATH=$JAVA_HOME/bin:$PATH` (`check_bytecode.py 69`). Anterior, Java 21: `export JAVA_HOME=$HOME/.cache/codex-java-upgrade/temurin21-21.0.13+9/jdk-21.0.13+9; export PATH=$JAVA_HOME/bin:$PATH`.
- Gates: `mvn -B -ntp clean verify`; `compare_surefire.py <antes> <depois>`; `python3 tools/check_bytecode.py 65`.
- Stack: `docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d` (imagens `:f6b` = Java 25; `:f4` = Java 21, `:f3`/`:f4a` guardadas). Espere o primeiro cadastro dar 200 antes de rodar fluxos.
- Pendencias abertas: ver "Pendencias finais" no plano e `FINAL-REPORT.md` (navegador real, API de cambio, imagem por digest, pagina de login nova do usuario ainda nao commitada, publicacao da branch da skill).

## Decisoes do usuario
D1 pontes F1 a F5 (escada de bytecode 8 -> 11 -> 17 -> 21). D2 Actuator + Micrometer + Prometheus + Grafana no lugar de Hystrix Dashboard/Turbine. D3 atualizar e substituir o necessario (Flapdoodle -> Testcontainers; `mongo:3` em etapa propria F4m, com restauracao do dump e retorno ensaiado). D4 login por authorization code com redirect aceito. D5 normalizado para LF (feito).

## Skill
Repo `/mnt/d/Projects/agent-marketplace`, skill `java-development:java-upgrade`, etapa `references/framework-major.md`. A skill sera preparada para o proximo LTS (Java 25) depois que a aplicacao estiver com runtime e bytecode em 21. O `AGENTS.md` do repo exige dois pareceres sequenciais de subagentes para mudancas de comportamento da skill; os commits recentes dela nao os tiveram.
