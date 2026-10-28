# E8 - Plano: framework para bytecode Java 21 (Boot 4.1 / Cloud 2025.1)

Estado: plano, nada executado. Segue a etapa `framework-major.md` da skill java-upgrade. Entrada: commit c675979 (runtime Java 21 aprovado, bytecode 1.8, 110 testes). Depois desta etapa: preparar a skill e subir o alvo para o proximo LTS (Java 25).

## Bloqueio comprovado

`mvn clean test -Djava.version=21` no gateway: classes major 65 e `Unsupported class file major version 65` no leitor de classes do Spring Framework 5.2 (Boot 2.3.12). Registrado em PLAN.md em 2026-10-27.

## Versoes alvo (consultadas em 2026-10-27)

| Item | Versao | Fonte |
| --- | --- | --- |
| Spring Boot | 4.1.2 (estavel; 4.2.0-RC1 ignorada) | maven-metadata.xml e https://docs.spring.io/spring-boot/system-requirements.html |
| Requisitos Boot 4.1 | Java 17+ (compativel ate 27), Spring Framework 7.0.10+, Servlet 6.1, Maven 3.6.3+, Tomcat 11 | mesma pagina |
| Spring Cloud | 2025.1.4 (Oakwood), suporta Boot 4.0.x e 4.1.x (4.1 desde 2025.1.2); 2025.0.x (Boot 3.5) esta EOL | https://spring.io/projects/spring-cloud |
| Gateway Server WebMVC | 5.0.4, gerenciado pelo train | maven-metadata.xml |
| Spring Security / Authorization Server | 7.1.2, incorporado ao Security | maven-metadata.xml, contrato E6 |
| Driver MongoDB | 5.13.0 no Central; versao efetiva e a gerenciada pelo Boot | maven-metadata.xml |
| Pontes (so se aprovadas) | Boot 2.7.18 + Cloud 2021.0.9; Boot 3.5.16 + Cloud 2025.0.3 (EOL) | maven-metadata.xml |

## Fatos do guia oficial Boot 4.0 que afetam o projeto

Fonte: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide (lido ate 100000 de 124659 caracteres).
- Atualizar antes para a ultima 3.5.x e resolver depreciacoes removidas.
- Starters modulares e renomeados: `web` vira `webmvc`; `oauth2-client`/`oauth2-resource-server` viram `security-oauth2-*`; testes exigem starters de teste por tecnologia.
- Jackson 3 (`tools.jackson`); `jackson-annotations` mantem coordenadas `com.fasterxml`. Anotacao `@JsonIgnoreProperties` do projeto nao muda.
- MongoDB: propriedades de conexao vao de `spring.data.mongodb.*` para `spring.mongodb.*`; UUID e BigDecimal exigem representacao explicita. A pagina nao cobre Flapdoodle; ver decisao D3.
- `@MockBean`/`@SpyBean` removidos (`@MockitoBean`/`@MockitoSpyBean`); `@SpringBootTest` nao fornece MockMvc nem TestRestTemplate sem anotacoes novas.
- Spring Authorization Server passou a fazer parte do Spring Security.

## Fatos do ecossistema Cloud (fontes oficiais)

- 2020.0: removidos `hystrix`, `hystrix-dashboard`, `ribbon`, `turbine`, `turbine-stream` e `zuul`; bootstrap nao e mais padrao (`spring.config.import` ou `spring-cloud-starter-bootstrap`). A pagina nao nomeia substitutos. https://github.com/spring-cloud/spring-cloud-release/wiki/Spring-Cloud-2020.0-Release-Notes
- 2022.0: Sleuth removido, substituto Micrometer Tracing; OpenFeign muda prefixo para `spring.cloud.openfeign`, `@EnableEurekaClient` removido, HttpClient 4 dropado. https://github.com/spring-cloud/spring-cloud-release/wiki/Spring-Cloud-2022.0-Release-Notes
- Cloud Stream: modelo funcional (`Function/Consumer/Supplier`) e o descrito como atual; a remocao de `@EnableBinding` e a versao NAO foram confirmadas. Consultar o guia de migracao do Stream antes de editar a turbine-stream e os `hystrix-stream`.
- Substitutos de Hystrix (circuit breaker), Ribbon (balanceamento) e Zuul (gateway): sem pagina que o diga nas fontes acima; usar a documentacao dos projetos Spring Cloud CircuitBreaker, LoadBalancer e Gateway. Para o gateway, o contrato E6 ja cita a pagina TokenRelay do Gateway Server WebMVC.

## Inventario por modulo (pom + anotacoes)

| Modulo | Itens legados |
| --- | --- |
| config | config-server, security |
| registry | eureka-server |
| monitoring | hystrix-dashboard (sem substituto direto) |
| gateway | zuul, sleuth, eureka-client |
| auth-service | spring-cloud-starter-oauth2 (servidor legado), sleuth, flapdoodle, jaxb |
| account-service | hystrix, hystrix-stream, bus-amqp, openfeign, sleuth, oauth2 client/resource-server, flapdoodle |
| statistics-service | idem, mais guava |
| notification-service | idem, mais mail |
| turbine-stream-service | turbine-stream, stream-rabbit |

## Rota definida: escada gradual de bytecode acoplada ao framework

Decisao D1 (tomada): pontes de framework em escada, uma mudanca por vez. O runtime e o JDK de build ficam em 21 durante toda a escada; sobe so o `release` do bytecode, e cada degrau remove um bloqueio nomeado. Degraus sem suporte oficial de manutencao (2.7, 3.5 para Cloud) vivem em branch de diagnostico e nao sao entregas de producao. O contrato E6 (auth e gateway direto em Boot 4) fica como atalho se um degrau provar-se inviavel.

| Etapa | Release | Framework / train | Mudanca | Bloqueio que remove | Gate de saida |
| --- | --- | --- | --- | --- | --- |
| F0 | 8 | 2.3.12 / Hoxton.SR12 | Teste de bytecode como gate; contratos de caracterizacao de login, fallback, balanceamento, bus, metricas | sem rede de seguranca para as substituicoes | 110 testes mais os novos, Java 21 |
| F1 | 8 | 2.3.12 / Hoxton.SR12 | Ribbon para LoadBalancer, Hystrix para Spring Cloud CircuitBreaker (Resilience4j), se Hoxton suportar (confirmar na fonte) | Cloud 2020.0 remove ambos; separa substituicao de upgrade | identidades preservadas, fallback e timeout observados |
| F2 | 11 | 2.7.18 / 2021.0.9 | upgrade de framework ainda com `javax`; `WebSecurityConfigurerAdapter` e depreciacoes; propriedades | guia do Boot 3 pede a ultima 2.7 antes (confirmar na fonte); isola falhas de config das de `jakarta` | reactor verde com classes major 55 |
| F3 | 17 | 3.5.16 / 2025.0.3 | `jakarta` por modulo, Sleuth para Micrometer Tracing, `spring.config.import`, Stream funcional, auth-service no Spring Authorization Server | Boot 3 exige Java 17; guia do Boot 4 pede a ultima 3.5; legado oauth2 nao roda em `jakarta` | reactor verde com major 61, login coberto |
| F4 | 21 | 4.1.2 / 2025.1.4 | starters modulares, Jackson 3, propriedades Mongo `spring.mongodb.*`, anotacoes de teste, Gateway Server WebMVC no lugar do Zuul | alvo final | reactor verde com major 65 |
| F5 | 21 | 4.1.2 / 2025.1.4 | imagens, compose completo, fluxos de negocio, retorno ensaiado, relatorio final | aceite do destino | `compare_surefire.py` contra a baseline original, fluxos validados |

Cada etapa: branch/commit proprio, Linux como gate principal, JARs e imagens anteriores guardados. Falha em um degrau abre diagnostico daquele degrau e nao avanca o seguinte. Se o degrau 11 provar-se desnecessario (F2 passa direto para 17 sem falhas atribuiveis ao bytecode 11), registre e funda F2 e F3 numa etapa so.

## Decisoes assumidas (reversiveis; ajuste antes da F0)

- D1: escada de pontes (acima).
- D2 (pesquisado em 2026-10-27, confirmado pelo usuario como direcao): o Hystrix Dashboard e o Turbine nao tem substituto de mesmo formato; a literatura e a documentacao convergem para Actuator + Micrometer + Prometheus + Grafana com as metricas do Resilience4j. Fontes: [Spring Cloud CircuitBreaker, Collecting Metrics](https://docs.spring.io/spring-cloud-circuitbreaker/reference/spring-cloud-circuitbreaker-resilience4j/collecting-metrics.html) (exige `spring-boot-starter-actuator` e `resilience4j-micrometer`; desliga por `spring.cloud.circuitbreaker.resilience4j.micrometer.enabled`), [Resilience4j Micrometer](https://resilience4j.readme.io/docs/micrometer) (series `resilience4j.circuitbreaker.state`, `failure.rate`, `slow.call.rate`), [Resilience4j Grafana](https://resilience4j.readme.io/docs/grafana-1) (dashboard oficial para importar), [VMware: substituicao do dashboard do circuit breaker](https://blogs.vmware.com/tanzu/replacing-the-spring-cloud-services-circuit-breaker-dashboard/) (Hystrix Dashboard com problemas de seguranca e fora de desenvolvimento). O dashboard Grafana 2113 e o 21307 dependem de Hystrix/Boot 3.x e nao servem sem validacao. Estado do repo: `monitoring` so tem `@EnableHystrixDashboard` e os `hystrix:` das configs compartilhadas; `turbine-stream-service` agrega `hystrix-stream` via RabbitMQ. Acao: remover os dois modulos Netflix depois de F1 e criar a visao Prometheus/Grafana no compose, sem nomes de metricas inventados (conferir as series no endpoint real).
- D3 (decidido pelo usuario: "atualize e substitua o que for necessario"): substituir Flapdoodle 1.50 (quebra em Java 21) por Testcontainers com MongoDB fixado; atualizar `mongo:3` para servidor suportado pela matriz do driver do Boot alvo. O servidor e uma etapa propria (F4m) dentro da F4: restaurar o `dump` atual no servidor novo, comparar os dados lidos pelas aplicacoes e ensaiar retorno; sem migracao destrutiva e sem tocar volumes existentes. Versao do servidor a fixar apos ler a matriz oficial do driver (fonte pendente).
- D4 (aceito pelo usuario, 2026-10-27): login por authorization code com redirect. Motivo citado: o login atual parece ter CSS/JS quebrado. Verificacao parcial: todos os recursos referenciados pelo HTML e pelo CSS respondem 200, jsdom executa os scripts sem erro (so o canvas, limitacao do jsdom) e `login()`/`requestOauthToken()` existem; `login.js` usa so CR como quebra de linha (valido em JS, herdado do upstream). Renderizacao visual nao verificada: faltam bibliotecas do sistema para o Chromium do Playwright (exige `sudo npx playwright install-deps`).
- D5 (feito, commit 45808e5): `.gitattributes` com `text=auto eol=lf` e arvore normalizada para LF; `.env` nao foi alterado.

## Retorno e riscos

Cada etapa em branch/commit proprio com JARs, imagens e configuracao anteriores guardados; tokens e sessoes emitidos pelo servidor novo nao valem no antigo. Risco principal: reescrever o servidor de autorizacao e o gateway ao mesmo tempo que o major; mitigado pela ordem F1 antes de F3/F4 e pelos contratos de F0. Nada aqui aprova deploy.

## Proximo alvo: Java 25

Boot 4.1 declara compatibilidade ate Java 27 (fonte acima), entao o salto 21 para 25 deve ser mais simples depois de F5. Antes, preparar a skill: ampliar tabela de transicoes (21 para 25), fontes oficiais do JDK 25, e validar no piloto. Nao iniciado.

## F0 - fechamento (2026-10-27)

Estado: concluida, com limites. Reactor Linux, Temurin 21.0.13, `mvn -B -ntp clean verify`: exit 0, 9 modulos SUCCESS, 113 testes. `compare_surefire.py` contra a baseline E4g (110): 0 removidos, 3 adicionados, 0 nao aprovados.

Entregue:
- `tools/check_bytecode.py MAJOR`: gate de bytecode; com 52 passa nos 16 conjuntos (main e test de 9 modulos), com 65 reprova, como esperado.
- `StatisticsServiceClientResilienceContractTest` (2 testes): balanceamento round robin por nome (Ribbon sem Eureka, 3/3 em 6 chamadas) e timeout Hystrix de 1500 ms com fallback sem excecao e latencia limitada.
- `StatisticsServiceClientDeadServerContractTest` (1 teste): com uma porta recusando conexao na lista, as chamadas nao lancam excecao e so 3 de 6 chegam ao servidor vivo (sem retry no proximo servidor). Uma substituicao que faca retry muda esse contrato e deve atualizar o valor junto com este plano.
- `account-service/pom.xml`: segunda execucao do Surefire (`isolated-resilience`) com `reuseForks=false` para essas duas classes. Ribbon, Archaius e Hystrix mantem estado estatico na JVM (circuitos abertos por testes de 503 e listas de servidores) e quebravam o balanceamento por nome quando outro contexto Spring rodava antes. Isolar todo o modulo custaria 8m30 contra 4m38 atuais.

Nao coberto (a tratar no inicio da F1, junto do desenho de metricas): Spring Cloud Bus/RabbitMQ, stream `hystrix.stream` (o endpoint de actuator responde 401 sem credencial na stack real), Hystrix no `notification-service`, login completo alem do `OAuthTokenContractTest` existente, Windows em Java 21.

## F1 - Ribbon para Spring Cloud LoadBalancer, Hystrix para Spring Cloud CircuitBreaker (Resilience4j)

Estado: `concluida` (2026-10-27).

### Entrada
- Revisao 62b4641 (branch `pilot/java8-to-21`), arvore limpa no inicio. Evidencia da F0 vale: nada mudou desde o fechamento (113 testes, `compare_surefire.py` 0 removidos).
- Stack local ativa (imagens Java 21 da F0): usada so para observar baseline de Bus/Rabbit, nao para aceite.

### Matriz
| Item | Valor |
| --- | --- |
| JDK de build/testes e JVM da aplicacao | Temurin 21.0.13, Linux (WSL2) |
| Release do bytecode | 8 (major 52), inalterado |
| Framework / train | Boot 2.3.12.RELEASE / Hoxton.SR12 (inalterado) |
| Resilience4j via circuitbreaker | `spring-cloud-circuitbreaker-dependencies` 1.0.6.RELEASE (gerenciado pelo Hoxton.SR12); versao do `resilience4j-*` a confirmar pela resolucao do Maven |
| Modulos | account-service, statistics-service, notification-service (clientes); gateway, monitoring e turbine-stream-service nao mudam em F1 |

### Fontes oficiais lidas (2026-10-27)
- Spring Cloud OpenFeign 2.2.x (Hoxton), https://docs.spring.io/spring-cloud-openfeign/docs/2.2.10.RELEASE/reference/html/: "If Spring Cloud CircuitBreaker is on the classpath and `feign.circuitbreaker.enabled=true`, Feign will wrap all methods with a circuit breaker"; nome do circuito `<feignClientName>_<metodo>` (a pagina mistura `#` e `_`); `fallback`/`fallbackFactory` iguais ao Hystrix; `spring-cloud-starter-openfeign` suporta Ribbon e `spring-cloud-starter-loadbalancer`; Ribbon e o padrao por compatibilidade e se desliga com `spring.cloud.loadbalancer.ribbon.enabled=false`. Decisao: Hoxton suporta ambos os substitutos no Feign.
- Confirmacao no artefato: `spring-cloud-openfeign-core` 2.2.9.RELEASE (Hoxton.SR12) contem `FeignCircuitBreaker`, `FeignCircuitBreakerTargeter` e `loadbalancer/FeignBlockingLoadBalancerClient` (`unzip -l`).
- Spring Cloud Commons 2.2.9 (Hoxton), https://docs.spring.io/spring-cloud-commons/docs/2.2.9.RELEASE/reference/html/: algoritmo padrao `RoundRobinLoadBalancer`; instancias estaticas pelo `SimpleDiscoveryClient` em `spring.cloud.discovery.client.simple.instances.<servico>[i].uri`, usado quando nao ha `DiscoveryClient` de registro; retry nao reativo so com Spring Retry no classpath, desligavel por `spring.cloud.loadbalancer.retry.enabled=false`; cache com ttl de 35 s (`spring.cloud.loadbalancer.cache.*`). A pagina nao manda excluir o starter do Ribbon; usa a propriedade.
- Spring Cloud CircuitBreaker 2.0.2 (https://docs.spring.io/spring-cloud-circuitbreaker/docs/2.0.2/reference/html/): configuracao por `Customizer<Resilience4JCircuitBreakerFactory>` e `TimeLimiterConfig`; propriedades `resilience4j.timelimiter.instances.*` vencem o Java config. ATENCAO: e a pagina de 2.0.2 (train 2020.0), nao a do 1.0.6 do Hoxton. A API de `Resilience4JCircuitBreakerFactory` deve ser conferida contra o 1.0.6 pelo comportamento nos testes.
- Spring Cloud Gateway/Zuul: o gateway ainda usa Zuul, que depende de Ribbon e de Hystrix; fica fora de F1 e sai na F4. Consequencia registrada: Ribbon continua no classpath so do `gateway` ate la.

### Mudanca pretendida
1. account-service: remover `spring-cloud-starter-netflix-hystrix`, `spring-cloud-netflix-hystrix-stream` e `@EnableCircuitBreaker`; adicionar `spring-cloud-starter-circuitbreaker-resilience4j` e `spring-cloud-starter-loadbalancer`; `feign.hystrix.enabled` vira `feign.circuitbreaker.enabled`; timeout de 10 s (`hystrix...timeoutInMilliseconds`) vira `TimeLimiter` equivalente.
2. statistics-service e notification-service: remover `spring-cloud-netflix-hystrix-stream`; `spring-cloud-starter-loadbalancer` onde ha chamada por nome (notification para account-service).
3. Excluir o Ribbon transitivo do `eureka-client` nesses tres modulos e fixar `spring.cloud.loadbalancer.ribbon.enabled=false`.
4. `spring-cloud-starter-bus-amqp` fica como esta (Bus nao e substituido em F1).
5. Os testes de contrato de Ribbon/Hystrix passam a expressar o mesmo contrato nas novas propriedades; o que mudar de valor e registrado aqui como mudanca deliberada, sem afrouxar asserts.

### Caracterizacao de baseline (stack local, 2026-10-27, antes de editar)
- RabbitMQ (API de management, guest): exchanges de usuario `springCloudBus`, `springCloudHystrixStream`, `hystrixStreamOutput` (topic). Bindings: 3 filas anonimas `springCloudBus.anonymous.*` (1 consumidor cada) e 1 fila `springCloudHystrixStream.anonymous.*` (turbine). Nenhuma mensagem publicada nos exchanges ate agora (`message_stats` vazio).
- Actuator dos tres servicos com token de client_credentials: so `health` e `info`. Nao ha `hystrix.stream` nem `metrics` expostos; a unica via de metrica de circuito e o stream Hystrix pelo Rabbit para o turbine.
- Efeito esperado de F1: Bus inalterado (3 filas); `springCloudHystrixStream` deixa de receber produtores; turbine e monitoring ficam sem origem de dados ate o passo F1b (D2: remocao dos dois modulos e visao Prometheus/Grafana). Decisao: F1b nao entra neste commit; fica `pendente` com esse motivo.
- Limites: sem Testcontainers no repo (D3 prevista para F4), a caracterizacao de Bus fica por observacao da stack real; os testes automatizados mantem `spring.cloud.bus.enabled=false`.

### Saida (gates)
`mvn -B -ntp clean verify` exit 0; `compare_surefire.py` baseline E4g contra o resultado: 0 removidos, adicionados explicados; `python3 tools/check_bytecode.py 52` passa; contratos de balanceamento por nome, timeout com fallback e servidor morto verdes (ou com valor atualizado e registrado); stack reconstruida com imagens novas e fluxo account -> statistics observado; Rabbit com mesmas 3 filas de Bus.

### Recuperacao
`git revert` do commit da F1 (sem dados envolvidos: nenhuma mudanca de esquema, token ou mensagem persistida); imagens anteriores `sqshq/piggymetrics-*` da F0 permanecem locais ate a recompilacao (guardar tag `f0` antes de reconstruir). Ensaio de retorno: pendente.

### Resultado (2026-10-27)
- Base: 62b4641 + alteracoes da F1 (commit desta etapa). JDK Temurin 21.0.13.
- `mvn -B -ntp clean verify`: exit 0, 9 modulos SUCCESS, 113 testes, 0 falhas, 0 ignorados.
- `compare_surefire.py` (baseline E4g, 110) contra o resultado (113): removidos=0, adicionados=3 (contratos da F0, um renomeado: `slowServerBeyondTimeLimitYieldsFallbackWithoutExceptionAndBoundedLatency`), nao_aprovados=0, ok=True.
- `python3 tools/check_bytecode.py 52`: todos os conjuntos ok.
- Contratos mantidos sem alterar asserts: round robin por nome 3/3 em 6 chamadas; fallback por timeout (1500 ms) com latencia < 4500 ms; servidor morto 3/6. Com LoadBalancer o comportamento observado e igual ao do Ribbon; nao ha mudanca deliberada nos contratos de balanceamento.
- Stack reconstruida (config, account, statistics, notification): RabbitMQ com as mesmas 3 filas `springCloudBus.anonymous.*` (1 consumidor cada); `hystrixStreamOutput` com taxa de publicacao 0.0 (o total antigo vem de auth/gateway, que seguem em Hystrix). Fluxo observado: `POST /accounts/` (account -> auth, 200), `PUT /accounts/current` (account -> statistics por nome via LoadBalancer, 200); statistics recebeu a chamada e falhou na API externa de cambio (401, falha externa conhecida), exercitando o fallback do account. Notification sobe sem erros; a chamada notification -> account depende do agendamento e nao foi disparada manualmente.

### Mudancas deliberadas
- Nao ha mais publicacao do stream Hystrix no RabbitMQ pelos tres servicos; turbine/monitoring ficam sem origem ate a F1b.
- `feign.hystrix.enabled` -> `feign.circuitbreaker.enabled`; timeout Hystrix de 10 s -> `TimeLimiter` de `piggymetrics.circuit-breaker.timeout` (10 s).
- Contorno do Sleuth 2.2.8 (`feignBuilder` simples sombreia o builder de circuit breaker): bean `feignCircuitBreakerBuilder` em `CircuitBreakerConfiguration`; remover junto com o Sleuth na F3.
- Ribbon, Hystrix e Archaius excluidos de eureka-client e openfeign nos tres modulos.

### Pendencias
- F1b (`pendente`): remover monitoring e turbine-stream-service, adicionar Prometheus/Grafana com nomes reais de metricas do endpoint e expor metricas Resilience4j. Auth e gateway ainda tem o stream Hystrix (gateway/Zuul sai na F4).
- A execucao surefire `isolated-resilience` (reuseForks=false) do account-service foi mantida; reavaliar se ainda e necessaria sem estado estatico de Ribbon/Archaius/Hystrix.
- Retorno: nao ensaiado. Tag `f0-final` e imagens `:f0` existem.

## F2 - Boot 2.7.18 / Cloud 2021.0.9, bytecode 11

Estado: `concluida` (2026-10-27). Bloqueios resolvidos por decisao do usuario (abaixo) e pela F1b (7724fc0). Resultado, mudancas deliberadas e pendencias no fim do documento.

### Entrada
- Revisao f2bab33 (F1 concluida), arvore limpa, 113 testes, bytecode 52. Runtime e build Temurin 21.0.13.

### Matriz (alvo)
| Item | Origem | Alvo |
| --- | --- | --- |
| Spring Boot | 2.3.12.RELEASE | 2.7.18 |
| Spring Cloud | Hoxton.SR12 | 2021.0.9 (openfeign/config/gateway 3.1.9, circuitbreaker 2.1.8, bus 3.1.3, sleuth 3.1.10, netflix 3.1.8, commons 3.1.8, stream 3.2.10; lidos no POM do BOM) |
| Bytecode | 8 (major 52) | 11 (major 55) |
| Namespace | javax | javax (jakarta so na F3) |

### Fontes oficiais lidas (2026-10-27)
- Boot 2.7.18, requisitos: "requires Java 8 and is compatible up to and including Java 21. Spring Framework 5.3.31 or above" (https://docs.spring.io/spring-boot/docs/2.7.18/reference/html/getting-started.html#getting-started.system-requirements). Decisao: Java 21 de runtime/build e suportado no alvo.
- Notas do Boot 2.7 (https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-2.7-Release-Notes): `WebSecurityConfigurerAdapter` depreciado (Security 5.7) em favor de bean `SecurityFilterChain`; `spring.data.mongodb.uri` vence host/porta; registro de auto-configuracao migra para `.imports` (spring.factories ainda lido); `@SpringBootTest` properties passam a vencer args de linha de comando.
- Compatibilidade do train (https://spring.io/projects/spring-cloud): 2021.0.x aceita Boot 2.6.x e 2.7.x (2.7 a partir de 2021.0.3); train marcado como fim de vida (sem suporte), coerente com o plano de degrau diagnostico.
- Notas do Cloud 2021.0 (wiki spring-cloud-release): cobre ate 2021.0.8; 2021.0.9 nao tem pagina, versoes lidas no POM do BOM em Maven Central (200 para `spring-cloud-dependencies:2021.0.9` e `spring-boot-starter-parent:2.7.18`).

### Achados que bloqueiam
1. O BOM `spring-cloud-netflix-dependencies:3.1.8` so contem Eureka (client/server) e Archaius. Nao ha `spring-cloud-starter-netflix-zuul` (ultimo 2.2.10.RELEASE), hystrix-dashboard nem turbine-stream. Afeta `gateway` (Zuul), `monitoring` (Hystrix Dashboard) e `turbine-stream-service`.
2. `spring-cloud-starter-oauth2` (auth-service) nao esta no BOM 2021.0.9 (ultimo 2.2.5.RELEASE, Hoxton). Precisaria de versao fixada fora do train; compatibilidade com Boot 2.7/Security 5.7 nao verificada em fonte oficial.
3. O plano previa Gateway Server WebMVC so na F4; o Spring Cloud Gateway 3.1.9 do train 2021.0 e reativo (WebFlux), nao servlet.

### Decisao (usuario, 2026-10-27)
Gateway reativo (Spring Cloud Gateway 3.1.9) na F2; F1b antes (feita); `spring-cloud-starter-oauth2` 2.2.5 fixado fora do BOM.

### Decisoes do usuario para destravar a F2 (2026-10-27)
- Gateway: Zuul -> Spring Cloud Gateway reativo dentro da F2.
- Monitoring/Turbine: fazer a F1b antes da F2 (D2).
- Auth: fixar `spring-cloud-starter-oauth2` 2.2.5 fora do BOM na F2 e validar por testes de login; Spring Authorization Server na F3.
- Estado da F2 passa a `pendente` ate o fim da F1b.

## F1b - Remover Hystrix Dashboard/Turbine, metricas por Actuator/Prometheus/Grafana

Estado: `concluida` (2026-10-27).

### Entrada
- Revisao 2bd82ae (F1 concluida). Bytecode 52, Boot 2.3.12 / Hoxton.SR12, Temurin 21.0.13.

### Matriz
Sem mudanca de versao de framework/JDK. Modulos removidos: `monitoring`, `turbine-stream-service`. Adicionados: `micrometer-registry-prometheus` (versao gerenciada pelo Boot 2.3.12) nos tres servicos cliente; servicos `prometheus` e `grafana` no compose (imagens fixadas apos leitura da fonte).

### Fontes oficiais
Ja registradas em D2 (Spring Cloud CircuitBreaker "Collecting Metrics", Resilience4j Micrometer/Grafana). Nomes de series so serao documentados depois de lidos em `/actuator/prometheus` real.

### Mudanca pretendida
1. Remover os dois modulos (pom raiz, Dockerfile, compose, `.travis.yml`, configs compartilhadas, README). Os 2 testes de contexto desses modulos saem com os modulos: remocao deliberada, nao enfraquecimento (compare_surefire deve listar exatamente esses 2 como removidos).
2. Expor `/actuator/prometheus` com acesso restrito a rede do compose (decisao de seguranca registrada na execucao).
3. Prometheus + Grafana no compose; nao publicar portas sem necessidade alem das de uso local.

### Saida (gates)
`mvn -B -ntp clean verify` exit 0; `check_bytecode.py 52`; `compare_surefire.py`: removidos = 2 (os dos modulos eliminados), 0 nao aprovados; stack sobe sem os dois servicos; `/actuator/prometheus` mostra series `resilience4j_circuitbreaker_*` reais apos uma chamada; Grafana le o Prometheus.

### Recuperacao
`git revert` do commit da F1b; imagens `monitoring`/`turbine-stream-service` anteriores continuam locais.

### Resultado F1b (2026-10-27)
- `mvn -B -ntp clean verify` no codigo final: exit 0, 8 modulos SUCCESS, 111 testes, 0 falhas, 0 ignorados. `check_bytecode.py 52`: ok.
- `compare_surefire.py` (baseline E4g): before=110 after=111 removed=2 added=3 not_passed=0, `ok=False` so por causa dos 2 removidos. Remocao deliberada: `MonitoringApplicationTests#contextLoads` e `TurbineStreamServiceApplicationTests#contextLoads` (modulos eliminados). Os 3 adicionados sao os contratos da F0.
- Stack: `/actuator/prometheus` na porta de gerenciamento 9100 de account, statistics e notification; Prometheus v2.55.1 com os 3 alvos `up`; Grafana 11.3.0 com datasource e dashboard provisionados (`observability/`) lendo o Prometheus. Series reais conferidas no endpoint: `resilience4j_circuitbreaker_state{state=...}`, `resilience4j_circuitbreaker_failure_rate`, `resilience4j_circuitbreaker_calls_seconds_{count,sum,max}{kind=successful|failed|ignored}`, com `name`/`group` = `StatisticsServiceClient#updateStatistics(String,Account)`. `resilience4j-micrometer` 1.7.0 ja vem transitivo do starter. Estado observado: `closed`; `failure_rate` -1 sem amostra minima.
- Seguranca: o filtro dos servicos tambem cobre a porta 9100, entao `EndpointRequest.to("prometheus")` foi liberado so para o endpoint de metricas nas tres `ResourceServerConfig`. A porta 9100 nao e publicada no host (conferido: `localhost:9100` recusa); na porta de negocio `/accounts/actuator/prometheus` continua 401. Sem teste automatizado dedicado a essa regra: lacuna registrada.
- Observacao: logo apos reiniciar o account-service, um `PUT` via gateway devolveu 500 uma vez (instancia ainda nao registrada no Eureka/Zuul); repetido depois, 200.

### Mudancas deliberadas F1b
- Modulos `monitoring` e `turbine-stream-service` removidos (pom, compose, `.travis.yml`, configs compartilhadas, README); portas 9000 e 8989 deixam de existir; compose ganha Prometheus (9090) e Grafana (3000).
- Dashboards do Hystrix deixam de existir; o dashboard Grafana proprio usa so series conferidas.

### Pendencias F1b
- Teste automatizado da regra do endpoint `prometheus`; alertas nao criados.
- Dashboard nao validado visualmente em navegador (apenas via API do Grafana).

### Mudanca pretendida (F2)
1. Pom raiz: Boot 2.7.18, Cloud 2021.0.9, `java.version` 11 (`release` 11; major 55).
2. Bootstrap deixa de ser padrao no Cloud 2020.0+: adicionar `spring-cloud-starter-bootstrap` onde ha `bootstrap.yml` (a troca por `spring.config.import` fica para a F3).
3. gateway: Zuul -> Spring Cloud Gateway 3.1.9 (WebFlux) com as mesmas rotas e prefixos (`/uaa/**` URL interna, demais `lb://`), timeouts de 20 s, cookies e Authorization repassados como no Zuul (`sensitiveHeaders` vazio); `spring-cloud-starter-loadbalancer`; estaticos do gateway servidos pelo WebFlux.
4. auth-service: `spring-cloud-starter-oauth2` 2.2.5 com versao explicita; `WebSecurityConfigurerAdapter` permanece (depreciado, nao removido no 2.7); validar login por testes.
5. Depreciacoes/propriedades do Boot 2.7 tratadas conforme aparecerem; nada de migracao `jakarta`.

### Saida (gates) F2
`mvn -B -ntp clean verify` exit 0 (JDK 21); `check_bytecode.py 55`; `compare_surefire.py` contra a baseline: removidos so os 2 da F1b, 0 nao aprovados; contratos de roteamento do gateway cobertos por teste novo (caracterizacao legado antes de remover o Zuul); stack reconstruida: login (password grant legado), cadastro, PUT account -> statistics, rotas do gateway e metricas Prometheus observados.

### Recuperacao F2
`git revert` dos commits da F2; tag `f1b-final` e imagens `:f1b` guardadas antes de reconstruir. Sem mudanca de dados/esquema.

### Resultado F2 (2026-10-27)
- Matriz final: Boot 2.7.18, Cloud 2021.0.9 (gateway/openfeign/config 3.1.9, circuitbreaker 2.1.8, bus 3.1.3, sleuth 3.1.10), bytecode 11 (major 55), runtime e build Temurin 21.0.13.
- Testes: 116 executados, 0 erros, 0 falhas, 0 ignorados. `check_bytecode.py 55`: ok. `compare_surefire.py` (baseline E4g): before=110 after=116 removed=2 added=8 not_passed=0, `ok=False` so pelos 2 removidos da F1b. Adicionados: 3 contratos da F0, 3 de `GatewayRoutingContractTest` e 2 de `OAuthTokenContractTest` (`serverScopeTokenMayCreateUsers`, `uiScopeTokenIsForbiddenFromCreatingUsers`).
- Rastreabilidade do gate: o `clean verify` do reactor inteiro ficou verde antes do ajuste de seguranca do auth. Depois do ajuste, o reactor completo foi rodado de novo e falhou so em `UserRepositoryTest` (auth), por causa da primeira versao da correcao; o auth-service foi corrigido e reverificado sozinho (`-pl auth-service clean verify`, 17 testes, exit 0). Os outros 6 modulos tinham passado no reactor com o mesmo codigo. Nao houve um unico `verify` do reactor inteiro com o codigo final.
- Roteamento: `GatewayRoutingContractTest` escrito sobre Zuul (commit bba71f5) passa sem mudar asserts sobre Spring Cloud Gateway 3.1.9 (reativo): prefixos sem strip, Authorization e Cookie repassados, `/index.html` servido e nao encaminhado, caminho desconhecido 404 sem chegar ao downstream.
- Stack reconstruida e observada: 5 servicos registrados no Eureka; `POST /accounts/` 200; login por password grant legado (`browser`, escopo `ui`) com token; `GET /accounts/current` 200; sem token 401; token `ui` em `POST /uaa/users` 403; `POST /uaa/users` com token `server` 200 direto e pelo gateway; `PUT /accounts/current` 200 com a chamada account -> statistics autenticada pelo token de servico (mesmo traceId nos dois); `GET /` 200 e caminho desconhecido 404 no gateway; Prometheus com os 3 alvos `up` e series `resilience4j_circuitbreaker_calls_seconds_count` para `AuthServiceClient#createUser` (3 successful) e `StatisticsServiceClient#updateStatistics`; Grafana health 200.
- Falha externa conhecida e preexistente: `GET https://api.exchangeratesapi.io/latest` devolve 401, o statistics falha, o circuit breaker registra `failed` e o fallback devolve 200 ao `PUT`. Por isso `/statistics/current` fica vazio nesta stack; a persistencia de estatisticas com cambio real nao foi observada.

### Achados e correcoes F2
- Boot 2.4+ sem JUnit 4 no starter de teste: `junit-vintage-engine` nos modulos de teste preserva as identidades.
- Boot 2.3+ sem validacao no starter web: `spring-boot-starter-validation` no auth-service.
- Flapdoodle: o pin 1.50.3 e incompativel com Boot 2.7 (`RuntimeConfig` ausente); removido, a versao gerenciada (3.4.11) exige `spring.mongodb.embedded.version`, fixada em 3.5.5 nos `application.yml` de teste dos 4 servicos.
- Mockito 4: `org.mockito.Matchers` removido; `ArgumentMatchers` em `MongoUserDetailsServiceTest`.
- Falha so vista na stack real: no auth-service, `@PreAuthorize("#oauth2.hasScope('server')")` (UserController) levantava `EL1011E ... on null context object` porque, sob Security 5.7, o autoconfigure legado deixou de aplicar o `OAuth2MethodSecurityExpressionHandler`; `POST /accounts/` devolvia 500. Os testes existentes nao exercitavam a expressao (o controller e testado em standalone). Correcao: `MethodSecurityConfig` expoe o handler como bean estatico; `@EnableGlobalMethodSecurity` continua em `AuthApplication` (movê-la quebrava o slice `@DataMongoTest`, que perdia o `ObjectPostProcessor`). Os 2 testes novos reproduzem o `EL1011E` sem a correcao (verificado desfazendo-a) e passam com ela.
- Token store em memoria do auth (`InMemoryTokenStore`, preexistente): reiniciar so o auth-service invalida o token de servico que account/statistics/notification mantem em cache e as chamadas autenticadas dao 401 ate esses servicos reiniciarem ou o token expirar (~12 h). Visto ao recriar so o auth; resolvido reiniciando os dependentes. Nao e regressao da F2; fica como risco operacional.

### Mudancas deliberadas F2
- Zuul substituido por Spring Cloud Gateway reativo (decisao do usuario); `gateway.yml` passa a usar `spring.cloud.gateway.*`; `@EnableZuulProxy` removido; o `reuseForks=false` temporario do Surefire do gateway foi removido.
- `spring-cloud-starter-oauth2` 2.2.5.RELEASE fixado fora do BOM no auth-service (decisao do usuario); `WebSecurityConfigurerAdapter` mantido.
- `spring-cloud-starter-bootstrap` adicionado onde ha `bootstrap.yml`.
- 2 testes novos em `OAuthTokenContractTest` (acima); Flapdoodle sem pin proprio.

### Pendencias F2
- O workaround `feignCircuitBreakerBuilder` (Sleuth) permanece ate a F3.
- Flapdoodle -> Testcontainers e `mongo:3` continuam na F4m; o mongod embutido 3.5.5 roda em Java 21 nesta maquina.
- Reavaliar a execucao `isolated-resilience` do Surefire no account-service (nao reavaliada na F2).
- Comportamento do gateway reativo com cookies/CORS no navegador nao foi verificado em navegador, so por curl e contrato.
- Rollback da F2 nao ensaiado. Fluxo de estatisticas com cambio real nao observado (API externa 401).
- Nao existe teste automatizado da regra `EndpointRequest.to("prometheus")` (F1b).

## F3 - Boot 3.5.x / Cloud 2025.0.3, bytecode 17, jakarta, Micrometer Tracing, Authorization Server

Estado: `em_execucao` (2026-10-27). Dividida em F3a e F3b (decisao abaixo); F3a primeiro.

### Entrada
- Revisao 0351a85 (F2 concluida), arvore limpa na branch `pilot/java8-to-21`, 116 testes, bytecode 55 (major). Runtime e build Temurin 21.0.13, Linux/WSL2.

### Matriz (alvo)
| Item | Origem | Alvo |
| --- | --- | --- |
| Spring Boot | 2.7.18 | 3.5.16 (existe em Maven Central; `spring-cloud-dependencies:2025.0.3` responde 200) |
| Spring Cloud | 2021.0.9 | 2025.0.3 (gateway 4.3.5, openfeign 4.3.3, netflix 4.3.3, config 4.3.4, commons 4.3.3, circuitbreaker 3.3.3, bus 4.3.2) |
| Bytecode | 11 (major 55) | 17 (major 61) |
| Namespace | javax | jakarta (so onde ha API de EE: validation, mail, servlet) |
| Tracing | Sleuth 3.1.10 | Micrometer Tracing (bridge Brave) |
| Config client | `bootstrap.yml` | `spring.config.import=configserver:` |
| Auth | `spring-cloud-starter-oauth2` 2.2.5 | Spring Authorization Server (F3b) |
| Testes Mongo | Flapdoodle embutido | Testcontainers |

### Fontes oficiais lidas (2026-10-27)
- Guia de migracao Boot 3.0 e notas do Cloud 2025.0: Java 17 minimo; Jakarta EE 9+; Flapdoodle sem autoconfiguracao no Boot 3; `management.<produto>.metrics.export` renomeado; correspondencia de barra final desligada por padrao.
- Cloud 2025.0 / compatibilidade do train (https://spring.io/projects/spring-cloud): 2025.0.x pareia com Boot 3.5.x. Starter do gateway reativo passa a `spring-cloud-starter-gateway-server-webflux` e propriedades a `spring.cloud.gateway.server.webflux.*`; cabecalhos `Forwarded` exigem `trusted-proxies`.
- Boot 3.5, tracing (https://docs.spring.io/spring-boot/3.5/reference/actuator/tracing.html): artefatos `io.micrometer:micrometer-tracing-bridge-brave` (+ `io.zipkin.reporter2:zipkin-reporter-brave` so se houver reporter) e `spring-boot-starter-actuator`; `management.tracing.sampling.probability` (padrao 10%); correlacao de log por `logging.pattern.correlation` (ex.: `[${spring.application.name:},%X{traceId:-},%X{spanId:-}] ` com `logging.include-application-name=false`, que reproduz o formato do Sleuth). `management.tracing.enabled`/`propagation.*` nao confirmados nessa pagina: conferir no apendice de propriedades antes de usar.
- Boot 3.5, Testcontainers (https://docs.spring.io/spring-boot/3.5/reference/testing/testcontainers.html): `spring-boot-testcontainers`, `org.testcontainers:junit-jupiter` e `org.testcontainers:mongodb`; exemplos so em Jupiter (`@Testcontainers`, `@ServiceConnection`, `MongoDBContainer`); `@DynamicPropertySource` como alternativa; aviso de que `@Container` estatico da extensao JUnit para o container ao fim da classe enquanto o contexto Spring pode ficar em cache; recomenda `@ImportTestcontainers`. Pagina nao cobre JUnit 4/`SpringRunner`.
- Spring Cloud OpenFeign (https://docs.spring.io/spring-cloud-openfeign/reference/spring-cloud-openfeign.html): `spring.cloud.openfeign.circuitbreaker.enabled`, prefixo `spring.cloud.openfeign.client.config.*`, Apache HttpClient 4 nao suportado (usar HC5), nomes de circuit breaker alfanumericos por padrao (`spring.cloud.openfeign.circuitbreaker.alphanumeric-ids.enabled=false` restaura os anteriores), Micrometer via `MicrometerObservationCapability`.
- Spring Authorization Server (visao geral, getting started, `AuthorizationServerSettings`) e Security 6.5 (method security, authorize-http-requests, opaque token): sem grant `password`; endpoints padrao `/oauth2/authorize`, `/oauth2/token`, `/oauth2/introspect`, `/oauth2/revoke`, `/oauth2/jwks`, `/userinfo`; `@EnableMethodSecurity`; `authorizeHttpRequests`/`requestMatchers`; token opaco por `spring.security.oauth2.resourceserver.opaquetoken.*`.
- Driver MongoDB (tabela de compatibilidade): comeca no servidor 4.2; `mongo:3` nao esta coberto.
- Paginas que devolveram 404 ou sem conteudo util: migracao Security 5.8/6.0 e protocolo de endpoints do SAS. Itens nao confirmados em fonte (campos da resposta de introspeccao, nomes exatos de propriedades de tracing) serao caracterizados por teste ou lidos no apendice antes de uso.

### Achados que bloqueiam
1. `spring-cloud-starter-oauth2` 2.2.5 (Spring Security OAuth legado) nao roda em Boot 3/jakarta; o Authorization Server novo nao tem password grant. O login do navegador, `check_token` e `/users/current` mudam de contrato.
2. Flapdoodle sem suporte no Boot 3: exige Testcontainers (D3).
3. `mongo:3` fora da tabela do driver 5.x: precisa ser medido; se falhar, a F4m (servidor) sobe para antes dos testes de F3.
4. Campos de introspeccao e mapeamento de authorities do SAS nao confirmados.

### Decisoes e assuncoes
- **Divisao F3a/F3b.** F3a: tudo menos o auth (Boot 3.5.x, jakarta, Micrometer Tracing, `spring.config.import`, gateway, Testcontainers, `SecurityFilterChain` dos servicos). F3b: auth-service no Spring Authorization Server, login do navegador por authorization code + PKCE (D4), servicos consumindo `/oauth2/introspect` e `/oauth2/token`. Motivo: o auth e o unico bloqueio de contrato (achado 1); isolar permite commit com gates verdes para o resto e deixa a mudanca deliberada de contrato num commit proprio. Os modulos compartilham o parent do pom raiz, entao o auth-service nao pode ficar em Boot 2.7 dentro do reactor: F3a e F3b sao executadas em sequencia na mesma branch e o commit de gates verdes so ocorre quando o reactor inteiro (incluindo o auth) passa; commits intermediarios, se houver, ficam marcados como nao verificados.
- Cliente publico `browser` com PKCE; tokens opacos validados por introspeccao.
- Testes continuam JUnit 4 via `junit-vintage-engine` (gerenciado no Boot 3.5); como a pagina de Testcontainers so mostra Jupiter, usa-se container compartilhado iniciado em holder estatico + `@DynamicPropertySource` (suportado pelo TestContext), e isso e uma assuncao a ser comprovada pelos testes.
- Imagem do Mongo nos testes: **medido** (account-service, `AccountRepositoryTest`, Testcontainers com `mongo:3`): o driver 5.5.2 do Boot 3.5 recusa o servidor (`Server ... reports wire version 6, but this version of the driver requires at least 8 (MongoDB 4.2)`). Decisao: testes usam `mongo:4.4` (banco vazio; configuravel por `piggymetrics.test.mongo-image`). O servidor de producao/compose (`mongodb/Dockerfile`, `FROM mongo:3`) continua sem driver compativel: a escada de servidor com restauracao do dump (F4m) deixa de ser opcional para rodar a stack com F3; para os fluxos vivos da F3 usa-se banco novo (sem dados migrados), e a migracao dos dados fica registrada como pendencia da F4m.

### Mudanca pretendida
F3a: (1) pom raiz Boot 3.5.x/Cloud 2025.0.3/`java.version` 17; (2) `javax.*` -> `jakarta.*` nos arquivos que usam validation/mail/servlet (arquivo a arquivo, sem substituicao global); (3) Sleuth -> Micrometer Tracing, padrao de log com `traceId`/`spanId`, remocao do workaround `feignCircuitBreakerBuilder` apos conferir que o problema nao se repete; (4) `bootstrap.yml` -> `spring.config.import` e remocao de `spring-cloud-starter-bootstrap`; (5) gateway: starter e propriedades renomeados, `trusted-proxies` se necessario; (6) `ResourceServerConfig`/`SecurityConfig` para `SecurityFilterChain`, `authorizeHttpRequests`, `requestMatchers`, `@EnableMethodSecurity`; (7) propriedades `management.*` renomeadas (prometheus); (8) Flapdoodle -> Testcontainers; (9) `mongodb/Dockerfile` so se a medicao exigir.
F3b: auth-service reescrito no Spring Authorization Server (clientes browser, account-service, statistics-service, notification-service), `launch.js` com authorization code + PKCE, servicos adaptados; testes de contrato antigos (password grant, `check_token`) substituidos por equivalentes, cada troca registrada como mudanca deliberada.

### Saida (gates)
`mvn -B -ntp clean verify` exit 0 (JDK 21), sem `skipTests`; `check_bytecode.py 61`; `compare_surefire.py` contra a baseline E4g com identidades adicionadas/removidas/alteradas listadas e motivo, `not_passed=0`; stack reconstruida com fluxos vivos (login, cadastro, PUT account -> statistics, rotas do gateway, series Prometheus, mesmo traceId entre servicos); build, testes e fluxos registrados separadamente; commit so com os gates verdes, sem push.

### Recuperacao F3
`git revert` dos commits F3a/F3b; tag `f2-final` no commit 0351a85 e imagens `:f2` guardadas antes de reconstruir. Sem migracao de dados do Mongo; tokens do auth sao em memoria. Rollback nao ensaiado ate registro em contrario.

### Resultado F3 (2026-10-27)
- Execucao: F3a (tudo menos auth) e F3b (auth no Spring Authorization Server, resource servers por introspeccao, `launch.js` com authorization code + PKCE) executadas.
- Matriz final: Boot 3.5.16, Cloud 2025.0.3, bytecode 17 (major 61), runtime e build Temurin 21.0.13, Micrometer Tracing (Brave), Testcontainers `mongo:4.4`.
- Build: `mvn -B -ntp clean verify` do reactor inteiro, JDK 21, sem `skipTests`: BUILD SUCCESS, exit 0 (repetido apos os dois ajustes dos fluxos vivos). `check_bytecode.py 61`: ok em todos os modulos.
- Testes (116 -> 119): auth 20, account 33, statistics 24, notification 34, gateway 3, config 5; 0 falhas, 0 erros, 0 ignorados. `compare_surefire.py` contra after2 (F2): before=116 after=119 removed=3 added=6 not_passed=0, `ok=False` so pela troca deliberada. Removidos: `browserPasswordTokenCanBeIntrospectedAndRefreshed`, `unknownTokenCannotBeIntrospected`, `wrongPasswordDoesNotIssueToken`. Adicionados: `browserAuthorizationCodeTokenCanBeIntrospected`, `unknownTokenIsReportedInactive`, `wrongPasswordDoesNotAuthenticateTheUser`, `passwordGrantIsNoLongerSupported`, `authorizationCodeWithoutPkceIsRejected`, `wrongPkceVerifierDoesNotIssueToken`. Os demais testes (incluindo o contrato de roteamento do gateway e os de Ribbon por nome) mantem identidade e asserts.
- Fluxos vivos (stack reconstruida, Mongo novo `mongo:4.4`): 5 servicos UP no Eureka; `POST /accounts/` 200; login por authorization code + PKCE pelo gateway (redirect para `http://localhost/uaa/login`, depois `?code=`), token trocado em `/uaa/oauth2/token`; `GET /accounts/current` 200, sem token 401; token `ui` em `POST /uaa/users` 403; `PUT /accounts/current` 200; `/notifications/recipients/current` 200; `GET /` 200, caminho desconhecido 404; Prometheus com 3 alvos `up`, series `resilience4j_circuitbreaker_calls_seconds_count`; Grafana 200; mesmo traceId entre account e auth (cadastro) e entre account e statistics (PUT).
- Nao observado: `/statistics/current` vazio porque `api.exchangeratesapi.io` devolve 401 (preexistente, circuit breaker `failed` e fallback 200).

### Achados e correcoes F3
- Gateway 4.3 so adiciona `Forwarded`/`X-Forwarded-*` se o endereco remoto casar com `trusted-proxies` (sem default); sem isso o `Location` do login expunha `auth-service:5000`. Corrigido em `shared/gateway.yml` com faixas privadas/loopback.
- Sem `io.github.openfeign:feign-micrometer` o Feign nao propagava o trace (traceIds diferentes); dependencia (gerenciada, 13.6.1) adicionada em account, statistics e notification.
- `mongo:3` e incompativel com o driver 5.x e o `/data/db` do 3.x nao abre no 4.4: `mongodb/Dockerfile` interino em `mongo:4.4`, volumes recriados (dados antigos descartados; migracao fica na F4m).
- Reiniciar so o auth invalida o token de servico em cache nos dependentes (token store em memoria, igual ao legado) e o cache do load balancer do gateway fica obsoleto apos recriar servicos; reiniciar dependentes/gateway resolve.

### Mudancas deliberadas F3
- Password grant removido; browser usa authorization code + PKCE (cliente publico `browser`, redirect `http://localhost/`, propriedade `piggymetrics.auth.browser.redirect-uris`); cadastro agora redireciona ao login da `uaa` em vez de token silencioso; sem refresh token para o cliente publico.
- `check_token`/user-info trocados por `/oauth2/introspect` (RFC 7662: token desconhecido 200 `{"active":false}`, `scope` string, `sub`); `/users/current` devolve so `{"name":...}`; cliente com segredo errado: 401 `invalid_client` em JSON.
- Propriedades: `security.oauth2.resource.user-info-uri` -> `introspection-uri` + `client-id`/`client-secret`; `accessTokenUri` -> `/uaa/oauth2/token`; segredos de clientes com BCrypt em memoria, cliente de servico ignorado com aviso se o segredo estiver vazio; `refresh_token` removido dos clientes de servico; TTL do access token mantido em 12 h.
- `server.forward-headers-strategy: framework` no auth; `trusted-proxies` no gateway; `bootstrap.yml` -> `spring.config.import`; Flapdoodle -> Testcontainers `mongo:4.4`.

### Pendencias F3
- Login por navegador real (PKCE em `crypto.subtle`, so em localhost/contexto seguro) nao exercitado; validado por curl e contrato.
- `mongodb/Dockerfile` em `mongo:4.4` e interino; escada de servidor e migracao de dados na F4m.
- `trusted-proxies` cobre faixas privadas; ajustar no deploy real. Rollback nao ensaiado (imagens `:f2` guardadas).
- Termos "legacy" remanescentes em docs E6; teste automatizado da regra `prometheus`; HANDOFF.md a atualizar.

## F4 - Boot 4.1.2 / Cloud 2025.1.4, bytecode 21, Jackson 3, starters modulares; F4m servidor MongoDB

Estado: `concluida` (F4a ea0fcd5, F4m f6cabc7, tag f4-final; registrado em 2026-10-27, antes de editar). Dividida em F4a (framework e bytecode 21) e F4m (servidor MongoDB do compose). Um commit verificado por parte, so com gates verdes, sem push.

### Entrada
Commit b6c02e1 (tag `f3-final`): Boot 3.5.16, Cloud 2025.0.3, bytecode 17, 119 testes verdes, fluxos vivos validados. Imagens `:f2` guardadas; imagens `:f3` serao marcadas antes de reconstruir.

### Matriz (alvo)
| Item | Versao | Fonte |
| --- | --- | --- |
| Spring Boot | 4.1.2 (Framework 7.0.10, Security 7.1.2, Tomcat 11.0.27, Jackson 3.1.7, Mockito 5.23.0, JUnit 6.0.3, Testcontainers 2.0.5, Micrometer 1.17.2, Micrometer Tracing 1.7.2, driver MongoDB 5.8.1) | `spring-boot-dependencies-4.1.2.pom` (Central) |
| Spring Cloud | 2025.1.4: gateway 5.0.4, openfeign 5.0.4, commons 5.0.4, config 5.0.6, circuitbreaker 5.0.3, bus 5.0.4, netflix 5.0.3. O BOM declara `spring-boot.version` 4.0.9; a compatibilidade com Boot 4.1 vem da pagina do projeto (registrada acima), a confirmar pelos testes | `spring-cloud-dependencies-2025.1.4.pom` |
| Bytecode / JDK | release 21 (major 65); build e runtime Temurin 21.0.13 | gate `check_bytecode.py 65` |
| Servidor MongoDB (F4m) | alvo `mongo:8.0`: driver 5.8.1 e totalmente compativel com 4.2 a 8.3 (tabela oficial); 8.0 EOL em 2029-10 | https://www.mongodb.com/docs/drivers/compatibility.md |

### Fontes oficiais lidas (2026-10-27)
- Guia de migracao Boot 4.0 (https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide, lido ate 100000 de 124659 caracteres): starters `web` -> `webmvc`; `oauth2-resource-server`/`oauth2-authorization-server`/`oauth2-client` -> `security-oauth2-*`; starters de teste por tecnologia (`*-test`); Jackson 3 (`tools.jackson`, `jackson-annotations` mantem `com.fasterxml`); `spring.data.mongodb.*` -> `spring.mongodb.*` (host, port, database, username, password, authentication-database, uri); UUID e BigDecimal sem representacao padrao; `@MockBean`/`@SpyBean` removidos; `@SpringBootTest` sem MockMvc/TestRestTemplate sem anotacoes; probes liveness/readiness ligadas por padrao; modulos `spring-boot-micrometer-tracing-brave`; Authorization Server dentro do Spring Security.
- Spring Cloud 2025.1 Release Notes (https://github.com/spring-cloud/spring-cloud-release/wiki/Spring-Cloud-2025.1-Release-Notes): so cita quebras por Jackson 3, JSpecify e Framework 7/Boot 4; detalhes por projeto nao estao na pagina.
- Gateway Server WebFlux 5.0.4 (https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/starter.html): `spring-cloud-starter-gateway-server-webflux`; a pagina nao documenta o prefixo das propriedades nem `trusted-proxies` (a conferir nos metadados do jar 5.0.4).
- Lacuna: guia de migracao do Spring Security 7 e notas por projeto do Cloud (feign, circuitbreaker, config, eureka, bus) nao lidos; as falhas reais de compilacao/teste os cobrem e cada achado sera registrado.

### Decisao de escopo
O gateway permanece Spring Cloud Gateway reativo (decisao do usuario na F2); a linha "Gateway Server WebMVC" da escada fica superada. `feign-micrometer` e `trusted-proxies` da F3 sao revisados contra os jars 5.x.

### Mudanca pretendida
F4a: (1) pom raiz Boot 4.1.2/Cloud 2025.1.4 e `java.version` 21; (2) starters renomeados e starters de teste por tecnologia, modulo a modulo; (3) Jackson 3 onde o codigo usar `com.fasterxml.jackson.databind`; (4) propriedades `spring.data.mongodb.*` -> `spring.mongodb.*`, representacao explicita de UUID/BigDecimal se usados; (5) `@MockBean`/`@SpyBean` -> `@MockitoBean`/`@MockitoSpyBean`, MockMvc/TestRestTemplate com as anotacoes novas; (6) Spring Security 7 (`SecurityFilterChain` ja usado; ajustar o que o compilador e os testes apontarem); (7) propriedades de gateway, tracing, actuator e Testcontainers 2 renomeadas conforme os jars; (8) bytecode 21 e `check_bytecode.py 65`.
F4m: `mongodb/Dockerfile` em `mongo:8.0`; `mongo` shell removido a partir do 6.0 -> `mongosh` em `init.sh`; restaurar o `dump` do repo, comparar leitura pelas aplicacoes e testes com `piggymetrics.test.mongo-image` no mesmo servidor. Dados dos volumes do `mongo:3` ja foram descartados na F3 (ambiente de desenvolvimento); nao havia dados a migrar alem do `dump` versionado.

### Saida (gates)
`mvn -B -ntp clean verify` exit 0 (JDK 21), sem `skipTests`; `check_bytecode.py 65`; `compare_surefire.py` contra a baseline F3 (after4) com identidades adicionadas/removidas/alteradas e motivo, `not_passed=0`; stack reconstruida com os mesmos fluxos vivos da F3 (login code+PKCE, cadastro, PUT account -> statistics, rotas, Prometheus, mesmo traceId); build, testes e fluxos registrados separadamente; commit sem push.

### Recuperacao F4
`git revert` dos commits F4a/F4m; tag `f3-final` (b6c02e1) e imagens `:f3` guardadas antes de reconstruir. Servidor MongoDB: volumes novos, nenhum volume existente tocado; voltar ao `mongo:4.4` exige volume novo ou dump (formato do 8.0 nao abre no 4.4 depois de FCV 8.0). Rollback nao ensaiado ate registro em contrario.

### Resultado F4a (2026-10-27)
- Build: `mvn -B -ntp -fae clean verify` verde (JDK Temurin 21.0.13), sem `skipTests`; `check_bytecode.py 65` ok em todos os modulos.
- Testes: 119 antes (F3) / 119 depois; `compare_surefire.py after4 after5`: removed=0 added=0 not_passed=0. Os `ServiceOAuthClientContractTest` (account e notification) mantem as asserts originais.
- Fluxos vivos (stack reconstruida, Mongo ainda 4.4): cadastro 200; login por authorization code + PKCE com `Location` `http://localhost/uaa/login`; token; `/accounts/current` 200 / sem token 401 / `ui` cria usuario 403; PUT account 200; notifications 200; gateway `/` 200 e rota desconhecida 404; Prometheus 3 alvos `up` e series resilience4j presentes; Grafana 200; mesmo traceId auth -> account.

### Achados e correcoes F4a
- Apache HttpClient 5 presente no classpath: o `RestClient` padrao do Spring Security 7 passa a usa-lo e ele repete um POST respondido com 503 (`HttpRequestRetryExec`), dobrando as chamadas ao emissor. Correcao: `ServiceOAuthClientConfiguration` (account e notification) usa `RestClientClientCredentialsTokenResponseClient` com `RestClient` sobre `JdkClientHttpRequestFactory`, conversor `OAuth2AccessTokenResponseHttpMessageConverter` e `OAuth2ErrorResponseErrorHandler`.
- Tracing: no Boot 4 a autoconfiguracao do Brave vive em `spring-boot-micrometer-tracing-brave`; so com `micrometer-tracing-bridge-brave` os logs saem com traceId vazio. Dependencia adicionada nos 5 servicos.
- `GET /statistics/current` retorna `[]` e o fallback de statistics do account loga erro: a API externa `api.exchangeratesapi.io` responde 401 `missing_access_key`. Nao e efeito do upgrade (dependencia externa sem chave); fica como pendencia.

### Mudancas deliberadas F4a
- `MediaType.APPLICATION_JSON_UTF8_VALUE` (removida) -> `APPLICATION_JSON_VALUE` (sem charset) nos clients Feign.
- `org.hibernate.validator.constraints.Email` (removida) -> `jakarta.validation.constraints.Email` em `Recipient`.
- Pacotes de slices de teste novos (`webmvc.test.autoconfigure`, `data.mongodb.test.autoconfigure`), `@MockBean` -> `@MockitoBean`, `spring.data.mongodb.*` -> `spring.mongodb.*`, `EndpointRequest` em `org.springframework.boot.security.autoconfigure.actuate.web.servlet`, `OAuth2AuthorizationServerConfigurer` instanciado diretamente (o metodo estatico `authorizationServer()` nao existe no Security 7).
- Cliente de token OAuth sem repeticao automatica (JDK HttpClient) por contrato de teste: uma tentativa por chamada.

### Resultado F4m (2026-10-27)
- Servidor: `mongodb/Dockerfile` em `mongo:8.0` (8.0.34 observado); volumes novos (os de 4.4 foram descartados com `rm -sfv`; 8.0 nao abre dados 4.4 sem passar por 5.0/6.0/7.0).
- `init.sh`: `mongo` -> `mongosh` (criacao do usuario e execucao do `INIT_DUMP`). Dockerfile: `--force-yes` removido do apt e `-y` no `dos2unix`.
- Dump: `db.accounts.update(doc, {upsert:true})` e rejeitado pelo `mongosh` ("Update document requires atomic operators"; o loop de retry repetia indefinidamente); trocado por `replaceOne` com o mesmo filtro, documento e `upsert`. Conteudo do documento `demo` conferido apos o restore.
- Testes: `piggymetrics.test.mongo-image` padrao `mongo:8.0` nos 4 modulos; `clean verify` verde (119 testes, `compare_surefire.py` contra after4: removed=0 added=0 not_passed=0), bytecode 65.
- Fluxos vivos sobre Mongo 8.0: mesmos da F4a (cadastro, login code+PKCE, token, accounts/current 200/401/403, PUT 200, notifications 200, rotas); sem erros de conexao Mongo nos 4 servicos; dados persistidos (accounts: demo + usuario do fluxo; users).
- Imagens `:f4a` guardadas antes da troca do servidor.

### Pendencias F4
- `exchangeratesapi.io` exige chave: `/statistics/current` vazio e fallback do account logando erro (externo, preexistente).
- Rollback F4 nao ensaiado; Security 7 migration guide e notas por projeto do Cloud nao lidos (cobertos por falhas reais).

## F5 - aceite do destino: imagens, compose completo, fluxos, retorno ensaiado, relatorio final

Estado: `concluida` (registrado em 2026-10-28, antes de executar; resultados abaixo). Sem mudanca de codigo prevista; so evidencias, documentacao e eventuais correcoes que os gates apontarem (cada uma registrada).

### Entrada
Commit 3e7c6b4 (tag `f4-final`, f6cabc7): Boot 4.1.2, Cloud 2025.1.4, bytecode 21, MongoDB 8.0, 119 testes verdes. Imagens `:f3` e `:f4a` guardadas; as `:latest` atuais (Mongo 8.0) serao marcadas `:f4` antes de qualquer recriacao.

### Matriz (alvo)
Igual a F4 (Boot 4.1.2, Cloud 2025.1.4, JDK Temurin 21.0.13, `mongo:8.0`, Prometheus v2.55.1, Grafana 11.3.0, RabbitMQ 3-management). Nenhuma versao nova entra na F5.

### Fontes oficiais lidas
Nenhuma nova; as da F4 permanecem. Comparacao com a baseline original usa os XMLs do E4g (110 testes) citados no HANDOFF.

### Mudanca pretendida
(1) Comparar as 119 identidades finais com a baseline original e listar adicionadas/removidas/alteradas com motivo. (2) Subir a stack completa a partir de volumes vazios com o compose (imagens reconstruidas) e repetir os fluxos de negocio, inclusive `demo` do dump e conferencia de metricas/traces. (3) Ensaiar o retorno em ambiente isolado: subir as imagens `:f3` (Mongo 4.4, volumes novos) com o compose e repetir o fluxo; depois voltar para `:f4`. (4) Relatorio final com o registro de evidencias da skill e atualizacao do `HANDOFF.md`.

### Saida (gates)
`compare_surefire.py` contra a baseline original com diferencas explicadas; `clean verify` ja verde em `f4-final` (nao muda); fluxos vivos na stack nova a partir de volumes vazios; retorno `:f3` ensaiado e resultado registrado; build, testes e fluxos separados no relatorio; sem push.

### Recuperacao F5
Nenhuma mudanca de codigo; volumes de desenvolvimento descartaveis. Estado anterior: tags `f4-final`, `f3-final`, imagens `:f3`/`:f4a`/`:f4`.

### Resultado F5 (2026-10-28)
- Testes: nenhuma mudanca de codigo; `clean verify` de `f4-final` verde (119 testes), bytecode 65 em todos os modulos. Contra a baseline original (110): removed=5 added=14 not_passed=0. Removidos: 3 de `OAuthTokenContractTest` com password grant (substituidos por authorization code + PKCE, passwordGrantIsNoLongerSupported, unknownTokenIsReportedInactive, wrongPasswordDoesNotAuthenticateTheUser; D4/F3) e `MonitoringApplicationTests`, `TurbineStreamServiceApplicationTests` (modulos removidos, D2/F1b). Adicionados: 3 contratos de resiliencia/balanceamento do account (F0/F1), 3 de roteamento do gateway (caracterizados em Zuul antes da F2) e 8 de auth (PKCE, escopos, introspeccao).
- Fluxos vivos a partir de volumes vazios (compose + dev, imagens reconstruidas): cadastro 200; login code+PKCE; token; `/accounts/current` 200/401/403; PUT 200; notifications 200; gateway `/` 200 e rota desconhecida 404; Eureka com 5 apps; Prometheus 3 alvos up e series `resilience4j_circuitbreaker_*` (aparecem depois da primeira chamada ao breaker); Grafana 200 com datasource Prometheus; documento `demo` restaurado no Mongo 8.0; mesmo traceId auth -> account. Unico ERROR nos logs: API externa de cambio sem chave.
- Retorno ensaiado em ambiente isolado (mesmo projeto compose, volumes novos): imagens `:f3` com Mongo 4.4.31 sobem e passam o fluxo; depois `:f4` com Mongo 8.0.34 sobem de volumes vazios e passam o fluxo. Primeira tentativa no `:f3` falhou no cadastro (nao no login): a stack tinha registrado 5 apps no Eureka mas o gateway ainda nao roteava para o account; `signup` foi repetido e passou nas tentativas seguintes. Dados nao migram entre 4.4 e 8.0; o retorno de dados e por volume novo ou dump.
- Runtime das imagens: `eclipse-temurin:21-jre-jammy` (tag flutuante; observado 21.0.12.1), build com Temurin 21.0.13.

### Pendencias finais
- Login por authorization code nao exercitado em navegador real (so curl); dashboard Grafana nao validado visualmente.
- API externa `exchangeratesapi.io` exige chave: estatisticas vazias (externo).
- Retorno ensaiado com imagens, nao com rebuild a partir das tags git; volumes e dados de producao nao existem neste ambiente.
- Tag da imagem JRE flutuante; fixar por digest em ambiente de entrega.
- Restos de exclusoes Ribbon/Hystrix nos poms, palavra "legacy" em docs E6, teste automatico da regra do endpoint `prometheus`, reavaliar a execucao `isolated-resilience` e o aviso de auto-attach do Mockito.
- Deploy em ambiente compartilhado nao autorizado por esta evidencia.

## F6 - Java 25: preparar a skill e validar no piloto (dependencias equivalentes)

Estado: `concluida` em 2026-10-28 (F6a, F6b, F6c). Decisoes do usuario abaixo.

### Entrada
`f5-final` (15745aa): Boot 4.1.2 / Cloud 2025.1.4, bytecode 21, 119 testes verdes, `:f4` guardada. A skill `java-upgrade` (repo `agent-marketplace`, commit 2ac2008) cobre ate 21: `references/migration.md` so tem as transicoes 8->11, 11->17 e 17->21 (25 cai em "Outros destinos"), `sources.md` aponta guias Oracle do 21, `framework-major.md` ja cita major 69 para Java 25, `check_bytecode.py` ja aceita 69.

### Lacunas conhecidas (a confirmar na fonte, nao assumidas)
1. Skill: linha de transicao 21 -> 25 (JEPs entregues em 22 a 25 que mudam comportamento ou removem API), guia de migracao Oracle do 25, notas de release do JDK 25 da distribuicao usada, ciclo de suporte do LTS 25 (para a regra "recomendar LTS suportado").
2. Skill: procedimento de "versoes equivalentes das dependencias": como achar, para cada dependencia/plugin/agente de bytecode, a menor versao estavel documentada para o JDK de destino (BOM do framework primeiro, depois o que o BOM nao gerencia: JaCoCo, Byte Buddy/Mockito, ASM, Lombok, plugins Maven, Testcontainers e imagens), com fonte e data. Sem matriz estatica de versoes.
3. Piloto, candidatos a bloquear (hipoteses a provar no JDK 25, nao fatos): `jacoco.version` 0.8.11 nas tres apps (versoes antigas leem ate um major limitado), maven-compiler/surefire, Mockito/Byte Buddy auto-attach (aviso ja registrado), Flapdoodle ja removido, imagens `eclipse-temurin:21-jre-jammy` e Temurin 25 para build, e o proprio Boot 4.1.2 (fonte do plano: ate Java 27; reconferir).
4. Skill: lacunas detectadas no processo F0-F5 (secao "Licoes F0-F5 para a skill" abaixo), a aplicar em F6a/F6c.
5. Gates ja existentes bastam: `check_bytecode.py 69`, `compare_surefire.py` contra `after7`, fluxos vivos de F5, retorno para `:f4`.

### Matriz (alvo, a preencher apos ler as fontes)
JDK de build e runtime: GraalVM Community Edition 25.0.x (patch a registrar; 25.0.2 e a ultima lida nas notas, de 2026-01-20, patches posteriores nao verificados). Imagem de runtime candidata `ghcr.io/graalvm/jdk-community:25` (tags `25.0.2`, `25-ol9`; multi-arch; licenca open source dentro da imagem), a confirmar no pull. Alternativa: Oracle GraalVM 25 (GFTC, base Oracle JDK 25, inclui CPUs). Bytecode 25 (major 69). Boot/Cloud: os atuais, salvo bloqueio provado. Dependencias fora do BOM: versao minima estavel documentada para 25, uma linha por item.

### Mudanca pretendida
F6a (skill, no repo `agent-marketplace`, branch propria): ampliar `migration.md`, `sources.md` e `framework-major.md`/novo trecho de dependencias equivalentes com fontes lidas; sem alterar o comportamento padrao (destino explicito continua mandando).
F6b (piloto, neste repo): diagnostico no JDK 25 sem editar (`mvn -fae clean verify`), prova do bloqueio por modulo, correcao minima de versoes (a skill manda tentar a menor mudanca antes de trocar framework), depois `java.version` 25, imagens JRE 25, gates e fluxos de F5.
F6a tambem aplica as licoes F0-F5 (abaixo); F6c: registrar o que o piloto mostrou na skill (achados reais, nao previstos), reavaliar o contrato.

### Saida (gates)
Piloto: `clean verify` verde no JDK 25 sem `skipTests`; `check_bytecode.py 69`; `compare_surefire.py` vs `after7` sem remocoes nem `not_passed`; stack reconstruida com os fluxos vivos de F5; retorno para `:f4` ensaiado. Skill: `AGENTS.md` do repo exige dois pareceres sequenciais de subagentes a cada marco (contrato, implementacao, piloto, publicacao), com respostas aos achados registradas; testes da skill (`test_compare_surefire.py`) verdes. Build, testes e fluxos registrados separadamente; nada de push nem deploy.

### Recuperacao F6
Piloto: `git revert` dos commits F6b, tag `f5-final`, imagens `:f4`. Skill: branch propria no `agent-marketplace`, nenhuma alteracao em `main` sem aprovacao.

### Licoes F0-F5 para a skill (propostas; nada aplicado ainda)
Cada item: o que faltou no processo, evidencia neste projeto e onde entraria na skill (`references/` do `agent-marketplace`).
1. BOM do train x framework escolhido: Cloud 2025.1.4 declara Boot 4.0.9 enquanto o alvo e 4.1.2; a compatibilidade so veio da pagina do projeto e dos testes. Registrar "versao que o BOM declara" ao lado da escolhida e tratar como hipotese ate o reactor passar. (`framework-major.md`, fontes e versoes)
2. Troca silenciosa de cliente HTTP/transporte: o `RestClient` padrao escolhe Apache HttpClient 5 se estiver no classpath, que repete um POST com 503 e dobrou as chamadas ao emissor de tokens (F4a). Apos um major, revisar cliente HTTP padrao, retry, timeouts e contagem de chamadas, e manter testes de contagem de chamadas como contrato. (`framework-major.md`, riscos silenciosos)
3. Observabilidade pode quebrar com testes verdes: trace ids vazios no Boot 4 por falta do modulo `spring-boot-micrometer-tracing-brave` (F4a); `feign-micrometer` ausente quebrou o traceId entre servicos (F3). O gate de fluxos vivos deve incluir correlacao de traces, `/actuator/prometheus` e series esperadas. (`evidence.md`/`framework-major.md`)
4. Defaults de seguranca que mudam redirects e cabecalhos: `trusted-proxies` do gateway sem default gerou `Location` com host interno (F3). Fluxos vivos devem seguir redirects de login como um navegador, nao so status. (`framework-major.md`)
5. Artefatos auxiliares de dados e imagens: `mongo` shell removido (mongosh), `update()` com documento de substituicao rejeitado, `--force-yes` do apt removido, dump reaplicado em loop infinito de retry (F4m). Checklist: scripts de init, dumps, healthchecks, Dockerfile e entrypoints do servidor de dados. (`framework-major.md`, "Dados")
6. Volumes de versao antiga nao abrem no servidor novo (mongo:3 -> 4.4 -> 8.0): usar volumes novos e documentar que o retorno de dados e por dump/volume, nao por `git revert`. (`framework-major.md`, `migration.md` retorno)
7. Prontidao em partida a frio: Eureka com 5 apps nao significa gateway roteando; o primeiro cadastro falhou no rehearsal de retorno. Esperar o primeiro fluxo de negocio responder 200 antes de medir. (`evidence.md`)
8. Estado em memoria e caches: tokens em memoria invalidados no restart, cache de load balancer do gateway obsoleto apos reiniciar dependentes. Registrar como risco operacional e reiniciar na ordem. (`framework-major.md`, riscos)
9. Substituicao de infra de teste: Flapdoodle -> Testcontainers exige Docker no gate e imagem do servidor de teste igual a do runtime (`piggymetrics.test.mongo-image`). (`framework-major.md`, entrada)
10. Comparacao em duas baselines: `compare_surefire.py` contra a etapa anterior E contra a baseline original, com removidos/adicionados explicados (F5). (`SKILL.md`/`evidence.md`)
11. Cobertura das fontes: guia de migracao lido so ate 100000 de 124659 caracteres e paginas sem os dados necessarios (prefixo de propriedades do gateway). Registrar trecho lido, lacunas e que as falhas reais cobrem o resto. (`sources.md`, `checkpoints.md`)
12. Imagem de runtime flutuante: JRE 21.0.12.1 na imagem x JDK de build 21.0.13. Registrar patch de ambos e fixar por digest na entrega. (`SKILL.md`, contrato e diagnostico)
13. Rollback por etapa funcionou: tags de git e de imagem por etapa (`:f3`, `:f4a`) e ensaio nos dois sentidos. Tornar o padrao de nomeacao e o ensaio bidirecional parte da etapa de retorno. (`framework-major.md`, gates)
14. Higiene operacional dos scripts de fluxo: rodar de um diretorio de rascunho para nao deixar `cj`/`login.html` no repo; notificacao de tarefa em background nao e fim do processo, verificar o processo. (`evidence.md`, nota curta)
15. Escada de bytecode 8 -> 11 -> 17 -> 21 validada: nenhum degrau falhou por causa do `release` em si; as falhas vieram de framework e bibliotecas. Registrar como evidencia da regra "fundir degraus sem falha atribuivel". (`framework-major.md`)

### Decisoes do usuario (2026-10-28)
1. Ordem: F6a minimo (skill) -> F6b (piloto no JDK 25) -> F6c (achados reais na skill).
2. Subagentes revisores: so quando eu julgar necessario, nao obrigatoriamente a cada marco. A instrucao do usuario prevalece sobre o padrao do `AGENTS.md` do repo da skill; registrar no relatorio de cada marco se houve ou nao parecer e por que.
3. JDK 25 em distribuicao que tambem ofereca GraalVM (o usuario pretende levar a aplicacao para Graal). Temurin deixa de ser restricao.

### Escolha da distribuicao (fontes lidas em 2026-10-28)
- https://www.graalvm.org/release-notes/JDK_25: GraalVM 25.0.0 (2025-09-16) em Oracle GraalVM (base Oracle JDK 25) e Community (base OpenJDK 25); CE 25.0.1 (2025-10-21, JDK 25.0.1+8) e 25.0.2 (2026-01-20, JDK 25.0.2+10). A pagina nao diz se 25 e LTS nem informa ciclo de suporte; isso vem do ciclo do JDK e precisa de fonte propria na F6a.
- https://docs.oracle.com/en/graalvm/jdk/25/docs/support: Oracle GraalVM 25 sob GFTC (uso comercial e em producao permitido; redistribuicao sem taxa); inclui os CPUs do Oracle JDK; a pagina nao informa LTS, duracao de suporte nem cobertura Java SE.
- https://github.com/graalvm/container: imagens `ghcr.io/graalvm/jdk-community` e `native-image-community` com tags `25`, `25.0.2`, `25-ol9`, amd64 e aarch64.
- Decisao: GraalVM Community Edition 25 para build e runtime no piloto (open source, mesma distribuicao serve ao futuro `native-image`, imagens oficiais). Execucao no piloto continua sendo bytecode na JVM; Native Image e outro escopo (skill `graalvm-migrate`). Lacunas: licenca exata do CE (relatos divergentes, ler o arquivo LICENSE do pacote), patch atual de 25.0.x, equivalencia de CPUs entre CE e Oracle GraalVM.

### Resultado F6a (2026-10-28)
- Branch `feat/java-upgrade-jdk25-lessons` em `agent-marketplace` (sem push, `main` intacta). Editados: `SKILL.md`, `references/{migration,sources,framework-major,evidence}.md`.
- Fontes lidas: guia Oracle 25 (indice, significant-changes 22-25, removed-apis, removed-tools; as paginas de removidos trazem so titulos), JEPs do JDK 25 (18, GA 2025-09-16), release notes GraalVM 25, suporte Oracle GraalVM 25, imagens CE. Lacunas: roadmap Oracle respondeu 403 (LTS 25 e datas de suporte vieram de busca, nao de leitura da pagina; por isso nao entram datas na skill); release notes do JDK 23 inacessiveis (processamento de anotacao desligado por padrao confirmado por busca e lista de discussao do OpenJDK, JDK-8321314); nao li o guia de migracao dos itens detalhados de seguranca.
- Skill: linha 21->25, secao "Dependencias equivalentes no destino" (sem matriz estatica), "Riscos silenciosos apos um major", cobertura das fontes, fluxos vivos e higiene, retorno bidirecional e duas baselines. Licoes 1-15 aplicadas; licao 15 como evidencia, nao regra.
- `python3 -m unittest discover -s scripts`: 17 testes ok. Revisores independentes: nao lancados (decisao 2 do usuario); mudanca e de texto de referencia, sem comportamento novo; revisar apos F6b (F6c).

## F6b - Piloto no JDK 25 (concluida; tag `f6b-final`)

### Entrada
HEAD `5f4a5ee` (worktree limpo para o diagnostico, pois o checkout principal tinha alteracoes do usuario: pagina de login propria no auth-service e ajustes em `gateway/static`, nao commitadas). JDK de build: GraalVM CE 25.0.2+10.1 (`$HOME/.cache/codex-java-upgrade/graalvm-ce-25.0.2/graalvm-community-openjdk-25.0.2+10.1`, tarball de github.com/graalvm/graalvm-ce-builds jdk-25.0.2, sha256 conferido, LICENSE.txt: GPLv2 com Classpath Exception, alguns modulos sob outras licencas). Maven 3.10.0.

### Diagnostico (sem editar, `mvn -B -ntp -fae clean verify`, `release` 21)
- Reactor inteiro SUCCESS em 1m35s; 119 testes, `compare_surefire.py` contra `after7`: removed=0 added=0 not_passed=0. O bytecode de 21 roda no JDK 25 sem alteracao.
- Achado silencioso: o agente JaCoCo 0.8.11 registra `Unsupported class file major version 69` (1114 vezes) e `68` (90) ao instrumentar classes do proprio JDK 25 (`sun/util/resources/...`); os testes passam, a cobertura dessas classes e perdida. Classes da aplicacao (major 65) nao falham. Fonte: https://www.jacoco.org/jacoco/trunk/doc/changes.html (consultada 2026-10-28): 0.8.14 (2025/10/11) suporta oficialmente Java 25; 0.8.16 (2026/10/24) e a mais nova estavel.
- Aviso do Mockito/Byte Buddy de agente dinamico persiste (preexistente).
- Nao testado ainda: `release` 25 (bytecode major 69) e imagens JRE 25.

### Mudanca
1. `jacoco.version` 0.8.11 -> 0.8.14 (menor versao com suporte oficial ao Java 25), verificar ainda com `release` 21 no GraalVM 25.
2. `java.version` 21 -> 25, depois gates: `check_bytecode.py 69`, `compare_surefire.py` vs `after7` (a pagina de login do usuario soma 1 teste, explicado), stack com imagens `ghcr.io/graalvm/jdk-community:25.0.2`, fluxos de F5, retorno para `:f4`.
Recuperacao: `git revert` dos commits F6b, tag `f5-final`, imagens `:f4`.

### Resultado F6b passo 1 (jacoco 0.8.14, release 21, GraalVM CE 25.0.2)
`mvn -B -ntp -fae clean verify` BUILD SUCCESS; zero linhas `Unsupported class file major version`; `compare_surefire.py` vs `after7`: ver commit. Hipotese do JaCoCo confirmada para este projeto (erro some ao trocar so a versao).

### Resultado F6b passo 2 (java.version 25, GraalVM CE 25.0.2)
`mvn -B -ntp -fae clean verify` BUILD SUCCESS (worktree limpo em 4227b2a + `java.version` 25); `check_bytecode.py 69` ok em todos os modulos (classes e test-classes major 69); `compare_surefire.py` vs `after7` (HEAD sem a pagina de login): before=119 after=119 removed=0 added=0 not_passed=0. Zero `Unsupported class file major version`.
Observado: aviso JEP 472 "restricted method in java.lang.System ... loadLibrary ... io.netty.util.internal.NativeLibraryUtil" (Netty 4.2.19, tambem com release 21 no JDK 25); `--enable-native-access=ALL-UNNAMED` o silenciaria, nao aplicado (aviso, nao falha; decidir se vale na entrega). Aviso do agente dinamico do Byte Buddy/Mockito persiste. Falta: imagens, stack, fluxos, retorno.

### Resultado F6b passo 3 (imagens e stack, 2026-10-28)
- Dockerfiles dos 7 servicos: `FROM ghcr.io/graalvm/jdk-community:25.0.2` (JDK completo, Oracle Linux 10.1, `java -version` GraalVM CE 25.0.2+10.1, `curl` presente para o HEALTHCHECK do config). Imagem de runtime = mesmo patch do JDK de build. Tag fixa de patch, nao digest (pendente para a entrega).
- Stack reconstruida a partir de worktree limpo (3 commits de F6b + Dockerfiles), projeto `piggymetrics-f6`, volumes novos, imagens tagueadas `:f6b`. `flow.sh` (F5): cadastro 200, login code+PKCE com redirecionamentos, token, `current` 200 / sem token 401 / escopo 403, PUT 200, notifications 200, gateway `/` 200 e rota desconhecida 404; Eureka com as 5 apps; Prometheus 3 targets up; trace ids presentes nos logs de account.
- Erros nos logs, todos conhecidos: `exchangeratesapi.io` 401 `missing_access_key` (account fallback e statistics) e `503 Load balancer does not contain an instance for auth-service` num POST de aquecimento antes de o auth registrar (partida a frio). Aviso JEP 472 do Netty no gateway.
- Retorno ensaiado: `piggymetrics-f6` removido com `-v`; `piggymetrics-f4r` com imagens `:f4` (JRE 21.0.12.1) e volumes novos: mesmos fluxos verdes. O sentido f4 -> f6b e o proprio build/up acima.
- Nao feito: a pagina de login nova do usuario (nao commitada) nao esta nesta stack nem na comparacao; navegador real; digest da imagem.
- Gates F6b: build verde sem skipTests; `check_bytecode.py 69` ok; `compare_surefire.py` vs `after7` 119/119 sem removidos/nao passados; fluxos vivos e retorno ensaiados.

### Resultado F6c (2026-10-28)
Skill (branch `feat/java-upgrade-jdk25-lessons`, commit e1e1421, sem push, `main` intacta): registrados o achado silencioso do JaCoCo (erros de agente com testes verdes; contar ocorrencias antes/depois), o aviso JEP 472 do Netty, o resultado "bytecode 21 roda no JDK 25 sem alteracao", a troca de imagem base (pacotes, usuario, curl do healthcheck, patch igual ao do build), o valor do gate de bytecode (pegou uma edicao nao gravada) e a regra de alteracoes alheias nao commitadas (worktree limpo, commit por caminho), alem do registro do que foi realmente pilotado (um projeto, Maven, Linux amd64/WSL2, sem Gradle nem Native Image). Testes da skill: 17 ok. Revisores independentes nao lancados (decisao 2); recomendavel um parecer antes de publicar a skill em `main`.

### Pendencias F6
Merge/publicacao da branch da skill (precisa de aprovacao); parecer independente opcional; digest da imagem GraalVM; decidir `--enable-native-access=ALL-UNNAMED`; commitar a pagina de login do usuario e validar no navegador; Native Image e outro escopo (`graalvm-migrate`); demais pendencias de F4/F5 seguem abertas.
