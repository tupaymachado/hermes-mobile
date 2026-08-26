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

### Estado
✅ **Concluída** (`feat/bot-mode-quick-switch`, PR #4). `BotSwitcherSheet` com VM de escopo Activity (deliberado — switch sobrevive ao dismiss), chip clicável no título do chat quando há bot ativo, strings en/ko/zh, 3 testes instrumentados. Desvios: "Ver todos" injetável como callback (testabilidade); review do Opus pegou 3 erros de compilação (smart cast em delegate, colisão BotsScreen composable/NavKey, createComposeRule sem `.activity`) corrigidos antes do merge.

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

### Estado
✅ **Concluída** (`chore/bot-mode-polish`). Fecha o MVP.

O que foi feito, e os desvios face ao plano:

- **Presence ao vivo.** `ChangeEvents.GATEWAY = "gateway.changed"` novo, parseado
  pelo `EventParser` junto dos outros quatro. O `refreshOnChange` ganhou uma
  sobrecarga que aceita **um conjunto** de tipos: o roster move-se por duas
  assinaturas (last message ← `sessions.changed`, presence ← `gateway.changed`)
  e dois coletores independentes teriam dois guards `refreshInFlight`
  separados — uma rajada nos dois tipos dispararia dois fan-outs concorrentes.
  Um backend que só emite um dos tipos degrada para esse; um que não emite
  nenhum fica no chão de sempre (pull-to-refresh + reentrar no ecrã).
- **Erro por bot ≠ roster partido.** O `BotsViewModel` já degradava por linha,
  mas a linha degradada era **indistinguível** de um bot sem conversas: ambas
  acabavam em `lastMessage = null` e mostravam "No messages yet" — ou seja, uma
  falha de rede era reportada como caixa de entrada vazia. Novo campo
  `BotRosterItem.lastMessageUnavailable`, com terceiro estado na linha
  ("Last message unavailable", em `colorScheme.error`). A linha continua
  selecionável: nada disto bloqueia o switch.
- **Acessibilidade.** O dot de presence passa a anunciar **sujeito + estado**
  (`"research: Offline"`, via `bots_presence_desc`) — uma caixa de 8dp a dizer
  só "Offline" é um estado sem sujeito na árvore de semântica. O `BotAvatar`
  ganhou `contentDescription` opcional (default `null` = decorativo): nos dois
  call sites da app o nome do bot é texto adjacente, e descrever o avatar
  duplicaria o anúncio; o monograma continua a nunca chegar à árvore de a11y
  ("RB" não é identidade).
- **i18n.** As 12 chaves `bots_*`/`screen_bots` existem nas três `values` (as
  duas novas incluídas); verificado por script sobre todos os `R.string.*`
  alcançáveis a partir das superfícies bots (13 chaves, incluindo as dos
  `StateViews` partilhados).
- **Dívidas da Fase 3.** (1) Tocar no bot já ativo era um no-op silencioso — a
  sheet fechava e não acontecia nada; agora o VM emite um toast
  "already active" e **não** chama o callback, portanto a sheet fica aberta.
  (2) A sheet fechava sem animação de saída: `selectBot` ganhou
  `onSwitched: () -> Unit`, que só corre quando o servidor aceita o flip, e a
  sheet pendura nele um `sheetState.hide()` + `invokeOnCompletion { … }`. O
  "Ver todos" também espera a animação — navegar primeiro destrói o ecrã
  anfitrião e leva a sheet a meio do slide. `onDismissRequest` continua direto:
  swipe e scrim já chegam com a sheet assente.

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

~~Fase 3 (switch rápido).~~ **MVP CONCLUÍDO** — Fases 0-4 todas mergeadas na `main` (ago/2026). Segue a seção **Pós-MVP** abaixo.

---

# PÓS-MVP — Features do Hermes Desktop ainda ausentes no mobile

Fonte: `website/docs/user-guide/bot-mode.md` + `features/*.md` do repositório upstream (52 docs de features no total). Ordenado por custo/benefício pro caso de uso Tupay (1 gateway, 3-6 bots, uso pessoal).

## PM1 — Bot-to-bot DMs legíveis (⭐ recomendado primeiro)

**O que é no Desktop:** bots trocam mensagens entre si com atribuição (`Message from 🤖 researcher (@researcher):`). O gateway injeta o protocolo de messaging no system prompt do canonical Bot Chat (`agent.bot_mode_protocol: true`, default ON) — **os bots já sabem conversar; o mobile só não mostra**.

**Estado atual no mobile:** as mensagens chegam às threads canônicas (Fase 2 garante), mas nada as distingue visualmente nem oferece visão agregada.

**Escopo:**
- Renderizar mensagens de bot-to-bot na ChatScreen com badge de autor (nome do bot remetente + avatar mini) — o conteúdo já vem no stream; falta parsear o prefixo `Message from 🤖 X (@x):` e estilizar
- Tela "Conversas entre bots": lista de threads onde há mensagens de bot→bot (query nas sessions por padrão de autor)
- Badge no roster quando um bot recebeu mensagem de outro bot
- Composer com @mention básico (autocomplete do roster) pra disparar handoff

**Arquivos prováveis:** `ChatScreen.kt` (render), `ChatViewModel` (parse do prefixo — mínimo), novo `ui/bots/BotDmsScreen.kt`, `BotsViewModel` (badge).

**Estimativa:** ~3-4 dias · 1-2 PRs. Risco baixo — tudo é leitura de estado que já existe.

## PM2 — Routines por bot + Bot switcher lateral (swipeable)

**Inspiração:** Spacek (cliente multi-agente). Adota os padrões visuais A/B/D e o gesto E — descartado C (quick-reply chips). Bot DMs (PM1) anotado pra revisão futura; sem ação aqui.

### A. "Created routine" / feedback inline de tool actions

Quando uma tool executa e produz um efeito colateral relevante (cron criado, mensagem enviada, schedule agendado, etc), o chat renderiza um **summary card inline** no histórico em vez de só o tool bubble cru. Mesma posição visual do Spacek ("Created routine • nome + ícone").

**Escopo:**
- Detectar eventos relevantes via o mesmo listener de tools do chat (provavelmente `ChatToolResult` ou `ToolCallSummary`)
- Modelo `ToolActionCard(action, label, icon)` derivado de `toolCallId` + outcome
- Renderiza antes do próximo user turn (não inline com tool call — não polui o debug)
- Strings novas (en/ko/zh)

**Arquivos prováveis:** `ui/chat/components/ToolActionCard.kt`, hook no `FullBleedChatList`, evento a ser decidido conforme o que o ChatViewModel já emite.

**Estimativa:** ~2 dias.

### B. Composer contextual por bot

Placeholder e ações do composer mudam conforme `activeProfileId` ativo:
- **Sem bot ativo:** placeholder e comportamento atuais (gateway raw)
- **Com bot ativo:** placeholder `Message <bot-display-name>`, hint sutil de que bot-to-bot @mentions estão disponíveis (sem ainda implementar o autocomplete — isso é PM4)

**Escopo:**
- `ChatComposer` (ou equivalente) lê `AuthManager.activeProfileId` + lookup do profile
- Strings com placeholder parametrizado (`R.string.chat_composer_placeholder_bot`, com `%1$s` = display name)
- i18n completa

**Arquivos prováveis:** `ui/chat/ChatComposer.kt`, `strings.xml` × 3.

**Estimativa:** ~1 dia.

### D. Avatar mais saturado + ring no bot ativo

Avatares do Spacek usam cores mais vibrantes e dão um ring ao redor do bot ativo. Nosso `BotAvatar` usa paleta de 4 slots do Material Theme — funciona mas é sóbrio.

**Escopo:**
- `BotAvatar(name, size, isActive)` — quando `isActive = true`, adiciona um Stroke/Border com cor primária
- Saturar levemente a paleta (ex: trocar `surfaceVariant` por algo mais distintivo)
- A11y: o ring **não** vira semantics — o avatar continua sendo decoration; o estado ativo já vem do nome+contexto visual

**Arquivos prováveis:** `ui/bots/components/BotAvatar.kt`, `BotRosterRow.kt`, `BotSwitcherSheet.kt`.

**Estimativa:** ~0.5 dia.

### E. Bot switcher lateral swipeable (ModalDrawer customizado)

Uma sidebar lateral esquerda, dismissible por gesto, que mostra a lista de bots como switcher persistente. Complementa (não substitui) o `BotSwitcherSheet` da Fase 3, que continua sendo o caminho rápido pelo título.

**Comportamento:**
- **Abrir:** swipe da borda esquerda pra direita (gesto padrão Material 3); ou tap no avatar/bot-name no header
- **Fechar:** tap no scrim, swipe pra esquerda, ou tap no bot selecionado (que também navega pro chat dele — duplo papel)
- **Conteúdo:** lista de bots igual à `BotRosterRow`, com o ativo destacado pelo ring (D)
- **Persistente sobre o chat:** o usuário volta exatamente onde estava

**Escopo:**
- Componente `BotSwitcherDrawer` (`ModalNavigationDrawer` ou `AnchoredDraggable` do Material 3)
- Integrar com `DrawerGestureController` (já existe — AGENTS.md exige screen-owned): declarar quais telas podem ativar o drawer de bots (Converse: Bots, Bot DMs, Chat; sub-pages: false)
- Tap no bot = switch via `BotsViewModel.selectBot()` + fecha drawer
- Avatar no header do chat vira o trigger secundário
- Não duplica o `BotSwitcherSheet` — só compartilha a fonte (`BotRosterItem` + `BotsViewModel`)

**Caveat:** o `ModalNavigationDrawer` raiz do Hermes já existe pro menu principal. Ou este é um **segundo drawer** empilhado (mais complexo) ou substituímos o `NavIcon.Menu` no header do Chat por um `NavIcon.Bots` que abre este. Decidir arquitetura antes de codar.

**Arquivos prováveis:** novo `ui/bots/components/BotSwitcherDrawer.kt`, mudanças em `ChatScreen` (header trigger), `HermesScaffold` (gesture opt-in por tela), `BotsViewModel` (reuso).

**Estimativa:** ~3 dias. Risco médio (gesture ownership é delicado — tem que coexistir com o menu principal sem brigar pelo gesto da borda).

---

### Tarefas de revisão postergadas

- **Bot DMs (PM1):** anotar pra revisitar. Atualmente é uma tela agregada de threads com tráfego bot-to-bot. Pode:
  - evoluir pra "inbox" de mentions (precisa PM4 pra saber de onde vem)
  - virar "Activity feed" do teu dia (combinado com sessions recentes)
  - ser escondida atrás de um toggle se ninguém usa
  - **Decisão: reavaliar após PM4 (autocomplete) — sem o autocomplete, "Bot DMs" é só arquivo de mensagens; com ele, vira inbox real.**

### Total PM2

```
A (cards)  →  B (composer)  →  D (avatar ring)  →  E (drawer)
  ~2d            ~1d              ~0.5d                ~3d
```

**~6.5 dias · 1-2 PRs.** Sugiro fatiar: PR1 = A+B+D (baixo risco, polish visual), PR2 = E (gesture ownership).

## PM3 — Group chats (2-6 bots)

**O que é no Desktop:** sala compartilhada. Tua mensagem dispara até 3 rodadas seriais; @menções escopam quem responde; bots se puxam com @name e te escalam com @user (badge "needs you"); caps 10 msgs/send e 3 rounds; cada bot mantém sessão própria `Group: <nome>` persistente. Salas são espelhadas nos gateways conectados (sobrevivem a desktop offline).

**Estado atual no mobile:** nada de rooms. A fundação (canonical chats, registry, switcher) cobre os blocos de UI individuais.

**Escopo (fases internas):**
- a) Modelo de room: sessão `Group: <nome>` por bot + mapeamento room↔membros (generaliza o mapa 1:1 do `BotChatRegistry` para 1:N — já previsto no design)
- b) UI da sala: mensagens com autor destacado, chips de membros, badge needs-you
- c) Orquestração de rounds: disparar turnos serialmente nos bots mencionados (ou todos), respeitando caps; o gateway serializa execução, o cliente coordena a sequência
- d) Criação/gestão de sala (escolher 2-6 membros, nomear, disbandar)

**Arquivos prováveis:** extensão do `BotChatRegistry` (1:N), novo `data/session/GroupRoomCoordinator.kt`, novo `ui/group/GroupRoomScreen.kt` + ViewModel, composer com @autocomplete.

**Estimativa:** ~2-3 semanas · 2-3 PRs. Risco médio-alto — orquestração multi-sessão é território novo no mobile.

## PM4 — @mention autocomplete no composer

**O que é no Desktop:** digitar `@` abre autocomplete validado contra o roster vivo; renomear um bot atualiza a tag; email/`@` desconhecido passa intacto.

**Escopo mobile:** autocomplete sobre o roster (nomes + títulos), inserção de token `@nome`, sem resolução cross-device no primeiro corte.

**Dependência:** faz mais sentido DEPOIS do PM1 (o handoff precisa de destino) ou PM3 (mentions em sala).

**Estimativa:** ~1-2 dias standalone.

## PM5 — Multi-source roster (bots de outros gateways)

Desktop agrega bots de múltiplas conexões (local + SSH + cloud) num roster só, com handles `@name-device` pra desambiguar.

**Relevância pro Tupay:** BAIXA hoje (1 gateway). Relevância futura: se tu espalhar bots entre Oracle A1 e outra máquina. Adiar até haver segundo gateway.

## PM6 — Avatares gerados por IA

Desktop usa `image.generate` RPC pra criar avatares. Backend não expõe campo de avatar no `ProfileInfo`; exigiria mudança server-side OU cache local do blob gerado. Cosmético; adiar.

## Ordem sugerida

```
PM1 (DMs legíveis)  →  PM2 (Routines)  →  PM4 (@mention)  →  PM3 (Group chat)  →  PM5/PM6
   ~3-4d                ~3-5d               ~1-2d              ~2-3 sem              adiados
```

PM4 depende funcionalmente de PM1/PM3 (precisa de contexto onde mencionar); PM3 é o único grande e pode ser fatiado (a→b→c→d como PRs separados).

## Nota de compatibilidade

Todas as features acima são **client-side sobre APIs existentes** — nenhuma exige mudança no gateway Hermes v0.20.x. O protocolo bot-to-bot e as salas espelhadas já são server-side no upstream; o mobile apenas consome. Isso significa: syncs futuros do upstream Hy4ri não conflitam com essas features (código novo em arquivos novos), e o Desktop permanece fonte da verdade pra qualquer estado compartilhado.
