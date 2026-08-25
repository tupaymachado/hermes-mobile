# Bot Mode MVP — Plano de Implementação

> Status: **MVP**, escopo reduzido (sem group chat, sem bot-to-bot messaging, sem routines pane).
> Validação inicial: 24/ago/2026 contra um gateway Hermes v0.20.5 vivo na tailnet.

## TL;DR

Implementar, em 5 PRs, **Roster de bots** + **Canonical Bot Chat por profile** + **Quick switch entre bot chats** no hermes-mobile. Estimativa: **~6 dias de um dev**. Fase 2 é a de maior risco.

---

## 0. Validação inicial contra gateway vivo (24/ago/2026)

Antes de começar, validei os pontos críticos do plano contra o gateway real:

### 0.1 `SessionInfo.preview` = primeiro user prompt (NÃO última mensagem)

Confirmado via `GET /api/sessions?profile=default&limit=5`. Exemplo:

```
title:  "Listar números de 1 a 80 com frases"  (gerado por LLM)
preview: "Conte de 1 a 80, um numero por linha, com uma frase curta sobre cada."  (1º user prompt)
```

**Implicação pro MVP:** o Roster **não pode usar `preview`** como last message. Precisa de endpoint dedicado:

### 0.2 Endpoint pra pegar última user message existe

```
GET /api/sessions/{id}/messages?limit=1&role=user
```

Retorna `{role, content, timestamp}`. **Funciona, é rápido.**

**Custo atualizado do fan-out do Roster:** N+2 requests (1 perfis + 1 sessions-perfil + N last-messages). Pra N < ~15 bots, OK.

### 0.3 `SessionInfo.pinned` existe e é manipulável

Sim, todas as 5 sessions retornadas vieram com `pinned=false`. Pode ser usado como marcador durável do canonical chat (com a ressalva de diferir o PATCH até haver server presence — ver §2).

### 0.4 `ProfileInfo.gateway_running` existe

Pode ser usado como presença binária (Online/Offline). Sessões ativas (`is_active=true`) podem marcar `ACTIVE`.

---

## 1. Leitura da arquitetura (base das decisões)

**Mapeamento conceitual adotado:** um *bot* = um **Hermes profile server-side** (`ProfileInfo` de `GET /api/profiles`), não o `ConnectionProfile` local (que é servidor+token). Os dois já são deliberadamente distintos no código (`AuthManager.activeProfileId` vs `selectedProfileId`), e o Bot Mode vive inteiramente sobre o primeiro.

Pontos da arquitetura que condicionam o plano:

| Peça | O que já existe | Implicação para o Bot Mode |
|---|---|---|
| `ProfilesViewModel.loadProfiles()` | `GET /api/profiles` + `/active` em paralelo; devolve `name`, `description`, `gateway_running`, `model` | Roster reusa isso quase inteiro; **não há campo de avatar** → avatar tem de ser derivado (monograma determinístico) |
| `ProfileScopeInterceptor` | injeta `?profile=<ativo>` em `/api/sessions` etc.; **param explícito ganha** | Para ler "last message" de *todos* os bots sem trocar de perfil, basta expor `profile` como `@Query` em `getSessions` |
| `WsProfileParams` + `WsMethods.PROFILE_SCOPED_METHODS` | injeta `params.profile` em `session.create/list/resume/delete/status` | O chat só re-escopa via re-dial do socket — o switch continua obrigatoriamente pelo `ProfileSwitchCoordinator` |
| `ProfileSwitchCoordinator.switchProfile()` | REST flip → `setActiveProfileId` → emite `switched` → `disconnect()`/`connect()` | É o único ponto legítimo de troca; o Bot Mode estende-o, não o contorna |
| `ChatViewModel` | collector de `switched` faz `resetSessionState(null)`; depois `handleGatewayReady()` → se `currentSessionId == null` usa `initialSessionId` **senão** `createNewSession()` | **Este `if` é o gancho exato do canonical chat**: já existe o caminho "abrir sessão específica após ready" (usado por notificações) |
| `SessionInfo` | tem `pinned`, `preview`, `title`, `started_at`, `message_count` | `pinned` serve como marcador durável server-side do canonical chat; **`preview` é o primeiro user prompt, NÃO a última mensagem** (validado) |
| `GET /api/sessions/{id}/messages` | retorna messages com `role`, `content`, `timestamp` | Fonte correta do "last message" do roster |
| `ChangeEvents.SESSIONS` + `refreshOnChange()` | broadcast `sessions.changed` com refresh silencioso | Roster atualiza last-message sem polling |
| `ServerStoreState` (DataStore, `ignoreUnknownKeys`) | campos opcionais com default | Mapa `profile → sessionId` cabe aqui sem migração |
| `ActiveSessionHolder`, `recoverGoneSession()`, `sessionHasServerPresence` | tratam sessão inexistente (4007/404) | O registry tem de fazer self-heal por cima destes caminhos |

**Armadilha crítica identificada:** `session.create` **não persiste linha no servidor até o primeiro prompt** (`sessionHasServerPresence`). Logo, `PATCH /api/sessions/{id}` com `pinned=true` numa sessão recém-criada dá 404. **O pin do canonical chat tem de ser diferido** até haver presença confirmada.

---

## Fase 0 — Fundação de dados (pré-requisito de tudo)

**Objetivo:** poder resolver, persistir e recuperar "a sessão canónica do bot X" sem UI.

### Editar
- `data/remote/HermesApiService.kt` — adicionar `@Query("profile") profile: String? = null` a `getSessions()` (o interceptor respeita param explícito; zero impacto nos callers atuais).
- `data/config/ServerStoreState.kt` — novo campo `val botChatSessions: Map<String, String> = emptyMap()` (chave = nome do profile).
- `data/local/AuthManager.kt` — acessores finos: `getBotChatSessionId(profile)`, `setBotChatSessionId(profile, id)`, `clearBotChatSession(profile)`.

### Criar
- `data/session/BotChatRegistry.kt` — objeto puro (sem Compose, sem Android) com a política de resolução:
  1. mapa local (`AuthManager`);
  2. fallback: sessão **pinned** mais recente de `GET /api/sessions?profile=X&limit=50&order=recent`;
  3. fallback: `null` → o chamador cria sessão nova;
  4. `adopt(profile, sessionId)` grava no mapa e agenda o pin;
  5. `flushPendingPin(sessionId)` faz o `PATCH {pinned:true}` só quando há presença server-side;
  6. `invalidate(profile, sessionId)` para o self-heal (sessão apagada noutro cliente).

### Testes
- `app/src/test/.../data/session/BotChatRegistryTest.kt` — ordem de fallback, pin diferido, invalidação, profile sem sessões.
- `app/src/test/.../data/remote/ProfileScopeInterceptorTest.kt` — reforçar o caso "param explícito vence" para `/api/sessions`.

### Estado
✅ **Concluída** (`feat/bot-mode-registry`, merged em `main`). `getSessions` ganhou `profile` explícito; `BotChatRegistry` cobre ordem de fallback, pin diferido e invalidação.

---

## Fase 1 — Roster de bots

**Objetivo:** ecrã de lista com avatar, presence e last message; tocar num item ativa o bot.

### Criar
- `ui/bots/BotsViewModel.kt` — `BotsUiState(bots: List<BotRosterItem>, activeBot: String?, isLoading, errorMessage, toastMessage)`.
  - `loadRoster()`: `getProfiles()` + `getActiveProfile()` em `async` (padrão de `ProfilesViewModel.loadProfiles`), depois **fan-out** `getSessions(profile = it.name, limit = 1, order = "recent")` por bot, em `async` com `coroutineScope`, teto de ~12 concorrentes.
  - Para cada session retornada: `getMessages(id, limit = 1, role = user)` pra pegar last user message real (NÃO usar `preview`).
  - Falha por bot degrada só aquele item (sem `errorMessage` global — regra já documentada em `loadModelOptions`).
  - `refreshOnChange(ChangeEvents.SESSIONS)` para o last-message.
  - `selectBot(name)` delega em `ProfileSwitchCoordinator` (Fase 2 liga ao canonical).
- `ui/bots/BotRosterItem.kt` — modelo de UI: `name`, `description`, `isActive`, `presence` (`ONLINE` = `gateway_running == true`, `ACTIVE` = é o profile ativo, `OFFLINE`, `UNKNOWN`), `lastMessage` (de `getMessages`, não de `preview`), `lastActivityAt`.
- `ui/bots/components/BotAvatar.kt` — monograma circular; cor determinística por `name.hashCode()` sobre uma paleta derivada de `MaterialTheme.colorScheme`/`LocalHermesStatusColors` (**nunca** literais `Color(0x…)` — guard `checkColorLiterals`).
- `ui/bots/components/BotRosterRow.kt` — avatar + nome + last message (1 linha, ellipsis) + dot de presence + check do ativo.
- `ui/bots/BotsScreen.kt` — `HermesScaffold(drawerGesturesEnabled = true)`, `LazyColumn` **sem** `.padding(paddingValues)` no conteúdo, `SkeletonListState`/`ErrorState`/`EmptyState` nos três ramos.

### Editar
- `NavigationKeys.kt` — `@Serializable data object BotsScreen : NavKey`.
- `ScreenRegistry.kt` — entrada em `DrawerSection.CONVERSE`, acima de `ProfilesScreen` (ícone `Icons.Filled.SmartToy`).
- `res/values/strings.xml` (+ `values-ko`, `values-zh`) — `screen_bots`, `bots_empty_title`, `bots_empty_desc`, `bots_presence_online/offline/active`, `bots_last_message_none`.

### Testes
- `app/src/test/.../ui/bots/BotsViewModelTest.kt` (MockK, padrão de `ProfilesViewModelTest`).
- `app/src/androidTest/.../ui/bots/BotsScreenTest.kt` — render do roster + estados vazio/erro.

### Estado
✅ **Concluída** (`feat/bot-mode-roster`).

Desvios face ao plano, todos deliberados:
- **`getSessionMessages` precisou de `@Query("role")`** — o client não expunha o
  param que o plano validou no gateway. Adicionado com default `null`; backends
  legados ignoram-no. Chamado com `order = "latest"` para paginar a partir da
  mensagem mais recente — sem isso um backend legado devolveria a mais antiga,
  que é exatamente o que `preview` já contém.
- **Teste instrumentado é `BotRosterRowTest`, não `BotsScreenTest`** — testa a
  linha do roster diretamente, sem precisar de encenar o fan-out de rede do
  ViewModel. O `BotsScreenTest` do plano faz mais sentido na Fase 3, junto com
  o switcher.
- **Paleta do avatar tem 4 slots** (pares de container do `colorScheme`), não
  uma paleta larga: as cores repetem-se a partir do 5º bot de propósito — a cor
  é auxílio de reconhecimento, o monograma e o nome é que identificam.

> **Nota de escopo:** `ProfilesScreen` **permanece** como ecrã de administração (criar/clonar/soul/model/rename). O `BotsScreen` é a superfície de conversa. Não fundir os dois no MVP.
>
> **Pitfall operacional:** ktlint vai falhar por causa de ordem de imports ASCII-lexicográfica (`LaunchedEffect` antes de `collectAsState`). Rodar `./ktlint --format` antes de push.

---

## Fase 2 — Canonical Bot Chat por profile

**Objetivo:** cada bot tem *uma* conversa persistente; entrar no bot reabre sempre a mesma thread.

### Editar
- `data/session/ProfileSwitchCoordinator.kt` — `switchProfile(name, targetSessionId: String? = null)`. Mantém `switched: SharedFlow<String>` intacto (não partir os 2 collectors existentes); guarda o alvo num campo `@Volatile pendingBotSessionId` consumido uma única vez. Ordem atual (flip → persist → emit → re-dial) **não muda**.
- `ui/chat/ChatViewModel.kt`:
  - no collector de `switched`: após `resetSessionState(null, …)`, `initialSessionId = ProfileSwitchCoordinator.consumePendingBotSession()`. `handleGatewayReady()` já faz o resto (`switchSession(initial)` em vez de `createNewSession()`) — **diff mínimo, zero duplicação de lógica de resume**.
  - quando `handleGatewayReady()` cai no ramo `createNewSession()` em contexto de bot: ao chegar o `session.create` result, `BotChatRegistry.adopt(activeProfile, id)`.
  - onde `sessionHasServerPresence` passa a `true` (REST 200 / resume ok / MessageStart): `BotChatRegistry.flushPendingPin(id)`.
  - em `recoverGoneSession(sessionId)`: `BotChatRegistry.invalidate(profile, sessionId)` antes do re-create, para o mapa não ressuscitar uma sessão morta.
- `ui/bots/BotsViewModel.kt` — `selectBot()` passa a: resolver via `BotChatRegistry` → `switchProfile(name, target)` → `NavigationController.navigateTo(ChatScreen)`.

### Testes
- `app/src/test/.../data/session/ProfileSwitchCoordinatorTest.kt` — target propagado e consumido uma só vez; ordem preservada.
- `app/src/test/.../ui/chat/ChatViewModelTest.kt` — `switched` com target → `initialSessionId` preenchido e `createNewSession` **não** chamado; pin diferido; invalidação em sessão 4007.

### Estimativa
~2 dias · 1 PR (`feat/bot-mode-canonical-chat`) — é a fase de maior risco (toca o `ChatViewModel`, 3.352 linhas, com máquina de generations/resume).

### Estado
✅ **Concluída** (`feat/bot-mode-canonical-chat`). O `ChatViewModel` mudou em
~50 linhas, todas penduradas em caminhos que já existiam.

Desvios face ao plano, todos deliberados:
- **O handoff carrega o PROFILE, não só o session id.** `consumePendingBotSession()`
  devolve `PendingBotChat(profile, sessionId?)`. Sem o nome do bot o caso mais
  comum de todos — abrir um bot pela primeira vez, quando ainda não há chat
  canónico — não teria como adotar a sessão criada, e a feature nunca
  arrancaria.
- **`switchProfile` ganhou um terceiro parâmetro, `isBotContext`** (default:
  `targetSessionId != null`). Um alvo nulo vindo do roster é indistinguível de
  um switch normal vindo do `ProfilesScreen`, e só o primeiro deve adotar a
  sessão criada. O `ProfilesViewModel` continua a chamar `switchProfile(name)`
  sem alterações.
- **Um switch normal LIMPA o handoff** em vez de o ignorar: um alvo armado que
  ninguém consumiu não pode reabrir a thread de um bot num switch feito noutro
  ecrã.
- **`recoverGoneSession` só invalida** (não re-arma a adoção). A sessão de
  recuperação não vira canónica sozinha; o próximo toque no bot resolve `null`,
  cria e adota. Menos estado, mesmo resultado.

---

## Fase 3 — Switch rápido entre bot chats

**Objetivo:** trocar de bot sem sair do chat.

### Criar
- `ui/bots/components/BotSwitcherSheet.kt` — `ModalBottomSheet` com a mesma lista da Fase 1 (reusa `BotRosterRow` e `BotsViewModel`), item ativo destacado, ação "Ver todos" → `NavigationController.navigateTo(BotsScreen)`.

### Editar
- `ui/chat/ChatScreen.kt` — o `title` passa a ser um chip clicável (`BotAvatar` pequeno + `state.chatTitle`/nome do bot) que abre o sheet; estado `showBotSwitcher` local, `rememberSaveable`.
- `ui/bots/BotsViewModel.kt` — nada estrutural; garantir que o VM do sheet é `viewModel { BotsViewModel() }` local ao sheet (não activity-scoped) para não arrastar estado.

### Testes
- `app/src/androidTest/.../ui/chat/ChatScreenTest.kt` — abrir o switcher pelo título; seleção invoca o switch.

### Estimativa
~1 dia · 1 PR (`feat/bot-mode-quick-switch`)

---

## Fase 4 — Polimento e blindagem

- Presence ao vivo: incluir `gateway.changed`/`sessions.changed` no `refreshOnChange` do roster; degradação silenciosa em backends sem `change_events`.
- Estados de erro por bot (last message indisponível ≠ roster partido).
- Acessibilidade: `contentDescription` no avatar e no dot de presence.
- i18n completa (`values-ko`, `values-zh`).
- `./ktlint --format` em todos os ficheiros novos.

### Extras fora do plano original (feitos sob demanda)
- **Login pré-populado com o gateway do Tupay**: `ServerEndpoint.DEFAULT_BASE_URL = http://100.101.230.70:9119/` (Tailscale, HTTP puro dentro do túnel — o warning de cleartext da tela é aceitável) + placeholder no campo URL. O default upstream (`https://127.0.0.1:9119/`) só faz sentido pra gateway on-device.

### Estimativa
~1 dia · 1 PR (`chore/bot-mode-polish`)

---

## Ordem de execução e totais

```
Fase 0 (dados)  →  Fase 1 (roster)  →  Fase 2 (canonical)  →  Fase 3 (switch)  →  Fase 4 (polish)
   0,5d              1,5d                 2,0d                  1,0d               1,0d
```

**Total: ~6 dias de desenvolvimento, 5 PRs.** A Fase 1 é entregável e demonstrável sozinha (roster funcional com switch normal de profile); Fases 0→1 podem ir num PR só se preferires um primeiro merge maior.

---

## Riscos e decisões que exigem confirmação

1. ✅ **`SessionInfo.preview` = primeiro user prompt** (validado). Last-message real vem de `GET /api/sessions/{id}/messages?limit=1&role=user`. **Custo:** +N requests no fan-out da Fase 1.
2. **Custo do fan-out:** 2N+1 requests no load do roster (1 perfis + 1 sessions-perfil + N last-messages). Aceitável para N < ~15 (caso típico). Acima disso, sugerir degradar para "last message só do bot ativo + lazy on-scroll" — decisão a tomar com dados reais.
3. **Pin como marcador durável** colide com o pin manual do utilizador em `SessionsScreen` (`togglePin`). Um utilizador que despinne o canonical chat perde só o *fallback* de recuperação cross-device — o mapa local continua a funcionar. Aceitável no MVP; vale um aviso no `SessionsScreen` numa iteração futura.
4. **`ChatViewModel` é o ponto quente.** A alteração é deliberadamente um só `if` reutilizando o caminho `initialSessionId` já existente (notificações), em vez de nova máquina de estados. Qualquer refactor maior da lógica de resume deve ficar fora deste MVP.
5. **Avatar** é monograma determinístico. Se houver intenção de avatares reais, o backend não expõe campo — exigiria mudança server-side (fora do escopo).

**Confirmado fora de escopo:** group chat, bot-to-bot messaging, routines pane. Nenhuma das fases acima cria estruturas que os bloqueiem — o `BotChatRegistry` é um mapa 1:1 que se generaliza depois para 1:N sem migração destrutiva.

---

## Próximo passo

Fases 0, 1 e 2 estão feitas: cada bot já tem uma conversa persistente e entrar
no bot reabre sempre a mesma thread. Segue a **Fase 3** (switch rápido entre bot
chats), que é puramente de UI — `BotSwitcherSheet` sobre o `BotsViewModel` que
já existe, aberto pelo título do `ChatScreen`. `selectBot()` já faz tudo o que o
sheet precisa (resolve canónico → switch → navega para o chat).
