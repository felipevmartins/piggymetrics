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

Estado: `em_execucao` (2026-10-27). Bloqueios resolvidos por decisao do usuario (abaixo) e pela F1b (7724fc0).

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
