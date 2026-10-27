# E6 — Authorization Server e gateway BFF no Java 21

Estado: em definição, sem alteração produtiva antes das revisões A/B.

## Entrada e objetivo

Entrada: E4g aprovado no JDK 11 com 110 testes/31 suítes e E5 concluído como investigação com falha classificada. E5 Windows Microsoft 21.0.12.1 e Linux Temurin 21.0.13 reproduziram a falha do `auth-service` legado em `jaxb-impl 2.3.0.1`/`Unsafe.defineClass`; a aplicação Java 21 não está aprovada. O diff local e os arquivos não rastreados existentes são trabalho autorizado e precisam ser preservados.

Este incremento substitui o Authorization Server legado e o Zuul que participa do login. Não corrige JAXB, não adiciona `--add-opens`, não mantém password grant customizado e não migra silenciosamente os demais serviços. `auth-service` e `gateway` passam a módulos Java 21 independentes dentro do mesmo reactor; root, config, registry, monitoring, account, statistics, notification e turbine permanecem temporariamente Boot 2.3.12/Hoxton/source-target 8 até contratos próprios. A heterogeneidade é transitória e não é aceite final da plataforma.

## Matriz e fontes fixadas

- Build/runtime dos dois módulos: Java 21, `release=21`; Windows Microsoft 21.0.12.1 e Linux Temurin 21.0.13.
- Spring Boot 4.1.2, Spring Framework 7.0.x e Spring Security 7.1.x gerenciados pelo Boot; Spring Cloud 2025.1.4 para gateway/config/discovery/load-balancing.
- Maven 3.6.3 ou posterior; versões efetivas precisam ser capturadas, não apenas declaradas.
- Fontes consultadas em 2026-10-26:
  - https://docs.spring.io/spring-boot/system-requirements.html — Boot 4.1.2 requer Java 17+ e suporta Java 21.
  - https://spring.io/blog/2026/10/23/spring-cloud-2025-1-4-has-been-released/ — Cloud 2025.1.4 suporta Boot 4.1.2.
  - https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/getting-started.html — starter, componentes e Java 17+.
  - https://docs.spring.io/spring-security/reference/7.0/whats-new.html — Authorization Server incorporado e password grant removido.
  - https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/core-model-components.html — grants/clientes suportados e secrets codificados.
  - https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webmvc/filters/tokenrelay.html — OAuth2 Login e TokenRelay no gateway MVC.

Antes de editar, confirmar resolução real dos artefatos 4.1.2/2025.1.4 no Maven e registrar qualquer divergência. Não substituir automaticamente por RC, snapshot ou outra linha.

## Contrato de segurança e comportamento

### Authorization Server

- Substituir `@EnableAuthorizationServer`, `AuthorizationServerConfigurerAdapter`, token store legado e Cloud OAuth pelo starter oficial do Authorization Server.
- Manter o context path externo `/uaa`. Usar endpoints padrão modernos sob ele: `/oauth2/authorize`, `/oauth2/token`, `/oauth2/introspect`, `/oauth2/revoke`, `/oauth2/jwks`, metadata OIDC, `/userinfo` e `/connect/logout`.
- Cliente confidencial `gateway`: `authorization_code` + `refresh_token`, `client_secret_basic`, redirect e post-logout URI explicitamente registradas, scopes `openid`, `profile`, `ui`. Nada de segredo no browser.
- Clientes `account-service`, `statistics-service` e `notification-service`: somente `client_credentials`, `client_secret_basic`, scope `server`; nenhum refresh token para serviço.
- Secrets vêm de configuração externa, precisam ser não vazios e são codificados pelo `PasswordEncoder`; não registrar valores nem incorporá-los ao repositório.
- Tokens de acesso JWT assinados. Produção exige chave RSA externa e estável; geração efêmera só é permitida em perfil de teste explícito. Falta/chave inválida deve falhar no startup, não gerar chave silenciosamente.
- Preservar `MongoUserDetailsService`, BCrypt dos usuários e criação/rejeição de duplicidade conforme contratos existentes. Migrar somente imports `javax` realmente exigidos para `jakarta`; nenhuma substituição global.
- `/users/current` continua protegido e devolve o schema temporário consumido pelos três adapters atuais: `name`, `oauth2Request.clientId` e `oauth2Request.scope`. Deve derivar identidade/client/scopes do token autenticado, sem confiar em campos do request.
- `POST /users` exige `SCOPE_server`. OIDC UserInfo e logout permanecem endpoints padrão. Erros não expõem token, secret, senha ou detalhes internos.

### Gateway BFF

- Substituir Zuul pelo Spring Cloud Gateway Server Web MVC 5.0.x gerenciado pelo BOM, com Config Client, Eureka Client, LoadBalancer, OAuth2 Client e observabilidade suportada. Não conservar Hystrix/Ribbon/Sleuth neste módulo.
- O gateway é cliente OIDC confidencial. Login começa em `/oauth2/authorization/piggymetrics`; callback e refresh ficam no servidor. `TokenRelay` envia access token às rotas protegidas. Browser não recebe access/refresh token.
- Remover do JavaScript password grant, Basic `browser`, `localStorage` e construção manual de `Authorization: Bearer`. Login passa a redirect; após cadastro bem-sucedido, redireciona para login OIDC. A etapa extra de autenticação após cadastro é mudança de UX registrada, sem perder capacidade de cadastro/login.
- Manter rotas e prefixos externos `/uaa/**`, `/accounts/**`, `/statistics/**`, `/notifications/**`; auth usa URL interna explícita, demais serviços usam `lb://`. Preservar timeouts de 20 s como valor inicial observável, sem equivaler Hystrix a Gateway.
- Recursos estáticos, cadastro `POST /accounts/` e demo `GET /accounts/demo` continuam públicos. Demais APIs exigem sessão autenticada. O gateway não deve aceitar token de usuário vindo de query/form.
- Como a sessão usa cookie, manter CSRF habilitado para operações mutáveis. Expor token CSRF em cookie legível pelo JavaScript e enviar `X-XSRF-TOKEN`; logout é POST com CSRF, limpa sessão/cookies e inicia logout OIDC. Não desabilitar CSRF globalmente para obter testes verdes.
- Preservar respostas/status relevantes das rotas e impedir forwarding de cookie/session/segredos para downstream além do access token necessário.

### Compatibilidade transitória

- Atualizar token URI de produção dos três clientes de serviço para `/uaa/oauth2/token`.
- Manter temporariamente `security.oauth2.resource.user-info-uri=/uaa/users/current`; adapters Boot 2.3 continuam validando o bearer por esse endpoint até migração JWT própria.
- O token de usuário relay deve produzir `name` igual ao usuário. Token `client_credentials` deve produzir principal/client identificável e scope `server`, suficiente para `POST /users` e chamadas internas existentes.
- Nenhuma migração de Mongo ou formato de documentos nesta etapa. Mongo de teste precisa ser substituído por fixture suportada em Java 21; não usar reflexão aberta para Flapdoodle 1.50.

## Implementação e gates

1. Snapshot: revisão/status, hashes dos arquivos pertinentes, JARs anteriores e configurações. Documentar recuperação antes da edição.
2. Caracterizar gateway legado: rotas/prefixos, públicos, header bearer, cadastro/demo, arquivos estáticos e comportamento de login/logout. Os testes OAuth legados permanecem evidência histórica; casos de password grant/check_token incompatíveis são substituídos por equivalências modernas explícitas, não apagados silenciosamente.
3. Implementar e testar primeiro `auth-service` focado no Java 21: client credentials positivo/negativos, authorization code/login/refresh, JWT/claims, UserInfo, introspection/revocation, `/users/current`, criação server-only, logout e falhas de configuração/chave.
4. Implementar e testar `gateway`: redirects/callback, sessão, refresh server-side, TokenRelay, CSRF, logout, rotas/prefixos, públicos e assets. Usar HTTP real entre auth e gateway para pelo menos login/callback/token relay; MockMvc isolado não comprova o fluxo distribuído.
5. Executar reactor Windows e Linux Java 21. Conferir XMLs realmente novos, identidade dos casos preservados/adaptados, Maven/forks, módulos alcançados, JARs e hashes de fontes. Um próximo bloqueio classificado encerra somente a investigação correspondente; não aprova módulos pulados.

Critério deste incremento: auth e gateway compilam/empacotam/executam no Java 21; contratos modernos acima passam em Windows e Linux; nenhum password grant/JAXB OAuth legado/Zuul/Hystrix/Ribbon/Sleuth permanece nesses dois módulos; fluxo HTTP real cobre login, refresh, relay e logout; fontes não mudam durante cada gate. Isso ainda não aprova todos os nove serviços, containers, observabilidade final ou dados compartilhados.

## Retorno

Preservar JARs/configuração anteriores e snapshot de auth/gateway. Retorno restaura somente arquivos E6 e executa a baseline JDK 11 anterior; não remove trabalho E4 do usuário. Como chaves/tokens/sessões mudam, rollback invalida tokens e sessões emitidos pelo E6; isso é esperado e precisa ser comunicado. Não há migration de banco. Ensaio de retorno em ambiente isolado é obrigatório antes de fechar E6; não há deploy compartilhado autorizado.
## Decisões incorporadas após revisão A

Estas decisões complementam e, em caso de ambiguidade, prevalecem sobre o texto anterior.

- **Maven e ordem:** cada módulo independente declara `groupId=com.piggymetrics`, versão, parent Boot, Java release, Surefire, JaCoCo, plugin Boot e, no gateway, BOM Cloud. O gate focado executa package do auth e depois do gateway. O teste HTTP inicia exclusivamente os dois JARs recém-construídos e confere seus SHA256; não depende da ordem atual dos módulos no root nem aceita JAR anterior.
- **Topologia OAuth:** issuer canônico do piloto `http://localhost:4000/uaa`, configurável por ambiente e invariável entre chamadas. Authorization/logout públicos passam pelo gateway. Token, JWKS, UserInfo e revogação usados pelo gateway são backchannels internos explícitos. O gateway cria `ClientRegistration` com issuer e todos os endpoints explícitos, sem discovery através do próprio gateway no startup. Redirect exato do piloto: `http://localhost:4000/login/oauth2/code/piggymetrics`; post-logout: `http://localhost:4000/`. Produção precisa declarar seus equivalentes exatos.
- **Fronteiras de sessão:** cookies distintos `PIGGY_GATEWAY_SESSION` (path `/`) e `PIGGY_AUTH_SESSION` (path `/uaa`). `/uaa/**` é permitido no filtro BFF e não recebe TokenRelay. Ao encaminhar auth, remover somente o cookie do gateway e preservar o cookie do auth e seus `Set-Cookie`; ao encaminhar APIs, remover `Cookie` e enviar apenas o bearer relay. Não enviar cookie/CSRF de uma fronteira para a outra.
- **CSRF:** auth mantém o CSRF dos próprios formulários/login. Gateway usa suporte SPA do Spring Security 7.1 (`csrf.spa()`), `CookieCsrfTokenRepository`, endpoint público `GET /csrf`, envio `X-XSRF-TOKEN` e renovação após autenticação/logout. Cadastro público continua exigindo CSRF. APIs anônimas retornam 401 para requisições AJAX; navegação HTML protegida redireciona ao login.
- **Claims e revogação:** access token contém `sub`, `client_id` gerado do cliente autenticado e `scope` como coleção. Usuário tem `sub=username`; serviço tem `sub=client_id`. `/users/current` emite `name`, `oauth2Request.clientId` e `oauth2Request.scope` array somente desses valores autenticados. Além de assinatura/expiração, consulta a autorização armazenada para rejeitar token revogado. Após `/oauth2/revoke`, introspection fica inativa e `/users/current` retorna 401. Logout BFF revoga access/refresh quando disponíveis, remove authorized client/sessão mesmo se a revogação remota falhar e realiza logout OIDC; nenhum token é exposto ao browser ou log.
- **PKCE:** o cliente confidencial gateway exige PKCE e envia challenge/verifier além de autenticar no token endpoint. O gate recusa authorization request sem challenge e callback sem verifier.
- **Cadastro/onboarding:** após cadastro, o browser guarda somente um marcador `sessionStorage` sem credencial/token e inicia OIDC. Após callback, o marcador abre a etapa de e-mail e é removido. Cadastro, login e onboarding continuam disponíveis, embora haja a autenticação explícita adicional registrada.
- **Mongo:** Spring Data MongoDB 5.1 é testado com servidores 6–8 e driver 5.6; o driver 5.2 removeu suporte ao MongoDB 3.6. Testes E6 usam Testcontainers com MongoDB 8.0 fixado. `mongo:3` e volumes existentes do compose não são usados nem migrados; compatibilidade produtiva fica bloqueada até contrato separado de upgrade/retorno de dados. Não alegar runtime completo no compose atual.
- **Imagens:** Dockerfiles de auth e gateway passam a imagem Java 21, constroem/executam os JARs release21 e são inspecionados/testados; os demais Dockerfiles Java8 permanecem fora deste incremento.
- **Gates adicionais:** provar um cliente Boot2.3 real obtendo `client_credentials` no novo `/uaa/oauth2/token` e validando os perfis user/service de `/users/current`; refresh BFF é provocado por token armazenado expirado, sem sleep; testar cookies por destino, CSRF inicial/renovado, 401 versus redirect, onboarding de e-mail, revogação e logout. O gate HTTP registra portas, issuer, hashes dos JARs e processos e encerra somente os processos que iniciou.

Fontes adicionais: https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html, https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorization-grants.html, https://docs.spring.io/spring-data/mongodb/reference/preface.html e https://www.mongodb.com/docs/drivers/java/sync/current/reference/upgrade/.
## Decisões incorporadas após revisão B

- O gateway ignora seu próprio CSRF **somente** para o matcher `/uaa/**`, porque essas requisições são protocoladas e protegidas pelo CSRF do auth upstream. `permitAll` sozinho não é considerado delegação. Antes de encaminhar `/uaa/**`, retirar `PIGGY_GATEWAY_SESSION`, o cookie `XSRF-TOKEN` e o header `X-XSRF-TOKEN` do BFF; preservar exclusivamente `PIGGY_AUTH_SESSION` e o campo/token CSRF emitido pelo auth. Testar POST de login pelo gateway com CSRF auth correto, ausente e inválido. As APIs e `/logout` do BFF continuam protegidos pelo CSRF do gateway.
+- Auth preserva Config Client e Eureka Client e por isso também importa explicitamente o BOM Cloud 2025.1.4 no POM independente. Nenhuma integração atual é removida por perda de herança.
+- Antes de TokenRelay, remover qualquer `Authorization` fornecido pelo browser. Se sessão, authorized client, obtenção ou refresh falhar, não chamar downstream e devolver 401/erro controlado, sem reaproveitar bearer do chamador. O gate verifica zero chamadas downstream.
+- URLs de authorization, callback, issuer e logout observadas pelo browser usam apenas a origem pública configurada. Hostnames internos aparecem somente em backchannels server-to-server. Forwarded headers ficam desabilitados no piloto direto; produção só poderá habilitá-los para proxies explicitamente confiáveis e com teste de spoofing.
+- Cadastro sempre inicia uma autorização com `prompt=login` e limpa a sessão BFF anterior. O caso de criar uma segunda conta com SSO da primeira precisa autenticar explicitamente a nova conta antes do onboarding; preferências nunca podem ser gravadas no usuário anterior.
+
+Com essas decisões, os pareceres A e B aprovam a implementação limitada de E6. Qualquer mudança posterior nessas fronteiras exige nova revisão antes do gate HTTP.