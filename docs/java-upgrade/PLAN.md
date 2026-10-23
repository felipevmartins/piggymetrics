# Java 8 → 21: piloto em checkpoints

## Estado

Destino autorizado: Java21, com substituições necessárias de componentes legados. A aplicação ainda está em Java8/Boot2.0.3/Finchley. Não há validaçãoJava21 nem substituição arquitetural implementada nesta etapa.

Baseline validada no WSL pelo usuário: execução run-20261023T205003Z-43637, Maven3.10.0, IBMJava1.8.0_482/Linux, reactor completo com exit0/BUILD SUCCESS,25suites/61testes sem falhas/erros/skips. Config e registry empacotam sem suítes de testes próprias. Os XMLs registram UTF-8 no runtime dos testes; a coleta de mvn-version inicialmente forçava LC_ALL=C apenas nesse comando e foi corrigida nas execuções seguintes.

Checkpoint local57045be preserva integralmente as19alterações anteriores do usuário (isolamento de serviços em testes, stubHTTP de câmbio, Dockerfiles e documentação). Elas pertencem à baseline, não foram criadas pela migração. Branch pilot/java8-to-21, sem push.

## Etapa atual: ferramentas, ainda Java8

Somente Surefire3.2.5 e JaCoCo0.8.11. Surefire herdado por propriedade do parent; quatro versões explícitas de JaCoCo agora usam a propriedade compartilhada. Mantidos prepare-agent, report, forks, código dos testes e assertions. Não adicionar exclusões ou skipTests para aprovar.

Gate: reactorLinuxJava8 com exit0, mesmos25nomes de suites/61casos, sem novos skips, agent/reportJaCoCo produzidos. Windows/Temurin8 é diagnóstico adicional, não substituto do ambienteLinux original. TentativasWindows anteriores esbarraram em forkSurefire e cache/diretório temporário do Mongo embutido; não atribuir essas falhas ao código de negócio.

ResultadoWindows deste checkpoint: Maven3.9.6/Temurin8u504, clean package com exit0 e BUILD SUCCESS nos nove módulos. Foram executados os mesmos61casos/25suites da baselineLinux, sem faltantes/adicionados e sem falhas/erros/skips. Logs confirmam Surefire3.2.5 nos nove módulos e quatro relatórios JaCoCo0.8.11 com classes/contadores não vazios. Cache user.home e java.io.tmpdir foram isolados em diretórios graváveis do workspace somente no comando de teste, sem alterar os POMs para contornar permissões. Gate Linux concluído: run-20261023T231053Z-52422, Maven3.10.0/IBMJava1.8.0_482, exit0 e BUILD SUCCESS nos nove módulos. Comparação dos XMLs confirma os mesmos25suites/61casos, zero falhas/erros/skips e nenhuma identidade adicionada ou removida.

## Próximas etapas

1. Após validar ferramentas, Boot2.1.18.RELEASE/Greenwich.SR6 ainda com source/targetJava8. São versões históricas para diagnóstico, não recomendação de produção. Conferir mudanças de segurança, Actuator, binding e sobrescrita de beans; não habilitar override global para esconder colisões.
2. Repetir o mesmo checkpoint com JDK11, separando nível do bytecode de runtime/build. Boot2.1.18 documentaJava8–12; não executar esse checkpoint em21 como se tivesse suporte oficial.
3. Fechar contrato das substituições necessárias rumo a plataforma suportada em21: OAuth2 (password/refresh/client_credentials, scopes e userinfo), gateway/headers/rotas, descoberta/balanceamento, Feignfallback/timeout/retry, notificações, tracing e agregação de métricas. Não remover monitoring/turbine para facilitar o build; substituições precisam de equivalência observável, não apenas compilação.
4. Implementar plataforma21 em etapas, preservar testes existentes e adicionar contratos distribuídos ausentes. Compose usa imagens remotas: construir imagens locais e registrar JDK/imageID/digest por serviço antes de alegar teste da versão migrada.

Retorno inclui artefatos, JDK/imagens/configuração e compatibilidade dos dadosMongo, não somente Git. Não foi executado deploy ou ensaio de retorno de dados. Nenhum resultado desta etapa certifica ganho causal da skill ou cobertura integral da aplicação.

## Revisões

Primeiro piggy_review_a aprovou a ponteJava11 com suporte documental, exigindo separar ferramentas/framework/JDK. Segundo piggy_review_b aprovou implementar inicialmente apenas ferramentas e exige gateLinux no mesmo ambiente. Ambos exigem preservação das61identidades de teste, nove módulos e contratosOAuth2/Netflix. Revisões internas não são auditoria externa.

## Fontes oficiais

- https://maven.apache.org/surefire-archives/surefire-3.2.5/maven-surefire-plugin/plugin-info.html — plugin executa emJava8/Maven3.2.5+.
- https://www.jacoco.org/jacoco/trunk/doc/changes.html — JaCoCo0.8.11 suportaJava21 e pluginJava8+.
- https://docs.spring.io/spring-boot/docs/2.1.18.RELEASE/reference/html/getting-started-system-requirements.html — ponteJava8–12.
- https://spring.io/projects/spring-cloud/ — matrizGreenwich/Boot2.1 e statusEOL.
- https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-2.1-Release-Notes — mudanças incompatíveis a analisar antes do framework.
- https://github.com/spring-cloud/spring-cloud-release/wiki/Spring-Cloud-2020.0-Release-Notes — remoção de integraçõesNetflix.
