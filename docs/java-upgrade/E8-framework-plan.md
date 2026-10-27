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
- D2: monitoring/turbine: substituir Hystrix Dashboard e Turbine por Micrometer + Prometheus/Grafana; verificar antes o que a pasta `monitoring` ja tem. Equivalencia de visao, nao identica.
- D3: testes de Mongo com Testcontainers e MongoDB 8.0 fixado (exige Docker nos testes). `mongo:3` das imagens permanece ate decisao separada de dados com retorno ensaiado.
- D4: login por authorization code com redirect, aceitando a mudanca de UX registrada.
- D5: nao normalizar CRLF agora; commits so com mudancas reais de conteudo.

## Retorno e riscos

Cada etapa em branch/commit proprio com JARs, imagens e configuracao anteriores guardados; tokens e sessoes emitidos pelo servidor novo nao valem no antigo. Risco principal: reescrever o servidor de autorizacao e o gateway ao mesmo tempo que o major; mitigado pela ordem F1 antes de F3/F4 e pelos contratos de F0. Nada aqui aprova deploy.

## Proximo alvo: Java 25

Boot 4.1 declara compatibilidade ate Java 27 (fonte acima), entao o salto 21 para 25 deve ser mais simples depois de F5. Antes, preparar a skill: ampliar tabela de transicoes (21 para 25), fontes oficiais do JDK 25, e validar no piloto. Nao iniciado.
