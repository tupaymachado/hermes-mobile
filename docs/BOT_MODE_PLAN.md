# Bot Mode — Plano & Histórico de Implementação

> Repo: `tupaymachado/hermes-mobile` (fork de `hermes-agent/hermes-mobile`)
> Validação inicial: 24/ago/2026 contra gateway Hermes v0.20.5 vivo na tailnet (100.101.230.70:9119).
> Atualizado em: 27/ago/2026, após o pivô de navegação (bottom nav) na branch `feat/bottom-nav-bot-mode`.

## TL;DR

- **MVP fechado**: Fases 0-4 (5 PRs) — registry → roster → canonical chat → quick switcher → polish. Tudo mergeado em `main`.
- **Pós-MVP em curso**:
  - ✅ PM1 — bot-to-bot DMs legíveis (PR #6)
  - ✅ PM2 PR1 — polish visual A/B/D (Spacek-inspired) (PR #7)
  - ❌ **PM2 PR2 (drawer swipeable E) — CANCELADO**. Substituído pelo pivô de navegação abaixo.
  - ⏳ **PM7 — bottom nav (Bots / Activity / More)**, branch `feat/bottom-nav-bot-mode`. Casca + feed implementados, PR por abrir.
  - 📋 PM3-PM6 — roadmap documentado, sem ação imediata.
- **Mapeamento conceitual**: um *bot* = um `ProfileInfo` server-side (não o `ConnectionProfile` local). Bot Mode vive sobre `AuthManager.activeProfileId`.

---

## Linha do tempo

| Quando | Fase / PR | Branch | O que |
|---|---|---|---|
| ago/2026 | Fase 0 | `feat/bot-mode-registry` | `BotChatRegistry` + `?profile=` no `getSessions` |
| ago/2026 | Fase 1 | `feat/bot-mode-roster` | `BotsScreen` + roster (fan-out last-message) |
| ago/2026 | Fase 2 | `feat/bot-mode-canonical-chat` | Canonical chat por profile (touchpoint: `ChatViewModel.handleGatewayReady`) |
| ago/2026 | Fase 3 | `feat/bot-mode-quick-switch` | `BotSwitcherSheet` no título do chat |
| ago/2026 | Fase 4 | `chore/bot-mode-polish` | Presence ao vivo, erro por bot ≠ roster partido, a11y, i18n completa |
| ago/2026 | PM1 | `feat/bot-dms-visible` | Bot-to-bot DMs renderizadas com badge de autor |
| ago/2026 | PM2 PR1 | `feat/bot-mode-visual-polish` (PR #7) | `ToolActionCard` (A) + composer contextual (B) + avatar ring/saturação (D) |
| ago/2026 | ~~PM2 PR2~~ | — | **Cancelado** — drawer swipeable (E) substituído pelo bottom nav |
| ago/2026 | **PM7** | `feat/bottom-nav-bot-mode` | Bottom nav (Bots / Activity / More) + feed de Activity |
| _futuro_ | PM3-PM6 | — | Group chat, @autocomplete, multi-source roster, avatares IA |

---

## 1. Decisões de arquitetura (ainda valem)

| Peça | O que já existia | Como o Bot Mode usa |
|---|---|---|
| `ProfilesViewModel.loadProfiles()` | `GET /api/profiles` + `/active` em paralelo | Roster reusa; **sem campo de avatar** → monograma determinístico |
| `ProfileScopeInterceptor` | injeta `?profile=<ativo>` em `/api/sessions` etc. | `getSessions` ganhou `@Query("profile")` (Fase 0) — param explícito vence o interceptor |
| `WsProfileParams` + `WsMethods.PROFILE_SCOPED_METHODS` | `params.profile` em `session.create/list/resume/...` | Chat só re-escopa via re-dial do socket; switch passa sempre pelo `ProfileSwitchCoordinator` |
| `ProfileSwitchCoordinator.switchProfile()` | REST flip → persist → emit `switched` → re-dial | Único caminho legítimo de troca. Bot Mode estende, **não** contorna |
| `ChatViewModel` | collector de `switched` → `resetSessionState(null)` → `handleGatewayReady()` (se `currentSessionId == null` usa `initialSessionId` senão `createNewSession()`) | Gancho do canonical chat: já existia o caminho "abrir sessão específica após ready" (notificações) |
| `SessionInfo` | `pinned`, `preview`, `title`, `started_at`, `message_count` | `pinned` = marcador durável do canonical; **`preview` = 1º user prompt, NÃO última mensagem** |
| `GET /api/sessions/{id}/messages?limit=1&role=user` | retorna `{role, content, timestamp}` | Fonte correta do last-message do roster |
| `ChangeEvents` + `refreshOnChange()` | `sessions.changed` + (Fase 4) `gateway.changed` | Roster move-se por **duas assinaturas** de `refreshOnChange` (last message vs. presence), cada uma com seu `refreshInFlight` |
| `ServerStoreState` (`ignoreUnknownKeys`) | campos opcionais com default | `botChatSessions: Map<String, String>` cabe sem migração |
| `ActiveSessionHolder` / `recoverGoneSession` / `sessionHasServerPresence` | tratam 4007/404 | Registry faz self-heal por cima |

**Armadilha crítica (ainda vale):** `session.create` **não persiste linha no servidor até o 1º prompt** (`sessionHasServerPresence`). Logo, `PATCH /api/sessions/{id}` com `pinned=true` em sessão recém-criada dá 404. **Pin do canonical tem de ser diferido** até presença confirmada — `BotChatRegistry.flushPendingPin(id)`.

**Invariante de UX (Fase 4, ainda vale):** o monograma do `BotAvatar` **nunca** vira semantics. Identidade vem do nome adjacente. O ring do avatar ativo (PM2 D) também **não** vira semantics — só decoração.

**Invariante de arquitetura (AGENTS.md, ainda vale):** drawer gesture é **screen-owned** (`HermesScaffold(drawerGesturesEnabled = ...)`). O bottom nav (PM7) **não toca nisso**: a barra é hospedada pelo `Scaffold` do `MainNavigation`, o drawer raiz continua sendo o único dono da borda esquerda, e nenhuma tela mudou sua preferência de gesto.

---

## 2. O que já foi feito (resumo)

### 2.1 MVP — Fases 0-4 ✅

**Fase 0 — Fundação de dados** (`feat/bot-mode-registry`)
- `HermesApiService.getSessions` ganhou `@Query("profile") profile: String? = null`.
- `ServerStoreState.botChatSessions: Map<String, String>` (sem migração).
- `AuthManager`: `getBotChatSessionId` / `setBotChatSessionId` / `clearBotChatSession`.
- `data/session/BotChatRegistry.kt` — objeto puro, sem Compose/Android. Política: mapa local → fallback sessão pinned → `null` → `adopt()` + `flushPendingPin()`; `invalidate()` pro self-heal.
- Testes: ordem de fallback, pin diferido, invalidação, profile sem sessões; reforço do caso "param explícito vence" em `ProfileScopeInterceptor`.

**Fase 1 — Roster de bots** (`feat/bot-mode-roster`)
- `BotsViewModel` com `BotsUiState`, fan-out `getMessages(id, limit=1, order=latest)` por bot (teto ~12 concorrentes), degrada por linha (não global), `refreshOnChange(SESSIONS)`.
- `BotRosterItem(name, description, isActive, presence, lastMessage, lastActivityAt, lastMessageUnavailable)`.
- `BotAvatar` (monograma circular, paleta 4 slots do `colorScheme` — `checkColorLiterals` guard), `BotRosterRow`, `BotsScreen` com 3 ramos (`LoadingState` / `ErrorState` / `EmptyState`).
- Strings en/ko/zh; `BotsScreen` no `DrawerSection.CONVERSE` acima de `ProfilesScreen` (`Icons.Filled.SmartToy`).
- **Desvio:** teste instrumentado é `BotRosterRowTest` (não `BotsScreenTest`) — `BotsScreenTest` faz mais sentido junto com o switcher (Fase 3).
- **Pitfall operacional:** ktlint falha por ordem ASCII-lexicográfica de imports (`LaunchedEffect` antes de `collectAsState`). `./ktlint --format` antes de push.

**Fase 2 — Canonical Bot Chat por profile** (`feat/bot-mode-canonical-chat`)
- `ProfileSwitchCoordinator.switchProfile(name, targetSessionId, isBotContext)`. Mantém `switched: SharedFlow<String>` intacto. Handoff via `consumePendingBotSession()` → devolve `PendingBotChat(profile, sessionId?)`.
- `ChatViewModel`: ~50 linhas, todas penduradas em caminhos que já existiam. `recoverGoneSession` só invalida (não re-adota).
- **Desvios deliberados:** handoff carrega o PROFILE (não só o session id — sem isso, o caso "abrir bot pela 1ª vez" não consegue adotar); `switchProfile` ganhou `isBotContext` (default: `targetSessionId != null`) para distinguir switch normal de switch-de-roster; switch normal **limpa** handoff armado (não ignora).

**Fase 3 — Switch rápido entre bot chats** (`feat/bot-mode-quick-switch`, PR #4)
- `BotSwitcherSheet` com VM de escopo Activity (deliberado — switch sobrevive ao dismiss), chip clicável no título do chat, strings en/ko/zh, 3 testes instrumentados.
- **Desvios:** "Ver todos" injetável como callback (testabilidade); review do Opus pegou 3 erros de compilação (smart cast em delegate, colisão `BotsScreen` composable/NavKey, `createComposeRule` sem `.activity`) corrigidos antes do merge.

**Fase 4 — Polimento e blindagem** (`chore/bot-mode-polish`)
- Presence ao vivo: `ChangeEvents.GATEWAY = "gateway.changed"` novo, parseado pelo `EventParser`. Roster move-se por **duas assinaturas** de `refreshOnChange` (uma por tipo de evento) com `refreshInFlight` separado — backend que emite só um tipo degrada pra esse; sem nenhum, fica no pull-to-refresh.
- `BotRosterItem.lastMessageUnavailable` — 3º estado na linha ("Last message unavailable" em `colorScheme.error`). Linha continua selecionável.
- A11y: dot de presence anuncia **sujeito + estado** (`bots_presence_desc`); `BotAvatar` `contentDescription` opcional (default `null` = decorativo).
- i18n: 12 chaves `bots_*`/`screen_bots` en/ko/zh + 1 do `StateViews` partilhado.
- Dívidas da Fase 3 corrigidas: tocar no bot já ativo agora emite toast "already active" e **não** chama o callback (sheet fica aberta); `selectBot` ganhou `onSwitched: () -> Unit` pra esperar animação de saída da sheet.
- Login pré-populado com gateway do Tupay (`http://100.101.230.70:9119/`, Tailscale).

### 2.2 Pós-MVP já entregue

**PM1 — Bot-to-bot DMs legíveis** (PR #6, `feat/bot-dms-visible`)
- Mensagens de bot-to-bot renderizadas na `ChatScreen` com badge de autor (nome + avatar mini do bot remetente), parseando o prefixo `Message from 🤖 X (@x):` injetado pelo gateway.
- Tela "Conversas entre bots" agregando threads com tráfego bot-to-bot.
- Badge no roster quando um bot recebeu mensagem de outro bot.
- Composer com @mention básico (autocomplete do roster) para disparar handoff.

**PM2 PR1 — Polish visual A/B/D** (PR #7, `feat/bot-mode-visual-polish`) ✅ **acabou de aprovar**
- **A — `ToolActionCard`:** card inline ("Created routine • name") pra tool rows que produziram side effect (cronjob create, send_message, etc.). Detecção payload-driven a partir do mesmo listener do `ChatToolResult`/`ToolCallSummary`. 12 testes unitários.
  - **Gap documentado:** transcript REST não traz `toolName`/`toolStatus` por design (issue #771) → cards só aparecem pra tool rows vistas ao vivo ou restauradas do Room. Ausência nunca vira card errado.
- **B — Composer contextual:** com bot ativo, placeholder vira "Message <display-name>". Sem bot, inalterado. Estados de conexão mantêm placeholders próprios. Strings parametrizadas (`%1$s`).
- **D — Avatar saturado + ring no ativo:** 4ª slot da paleta troca de `surfaceVariant` pra `secondary` (mais vibrante). `isActive = true` adiciona ring de 2dp na cor primária. Ring **não** vira semantics (invariante da Fase 4).
- Validação: 1190 testes verdes local, ktlint limpo.

### 2.3 Extras sob demanda (já no `main`)
- `ServerEndpoint.DEFAULT_BASE_URL = http://100.101.230.70:9119/` (Tailscale, HTTP puro dentro do túnel) + placeholder no campo URL de login. Default upstream (`https://127.0.0.1:9119/`) só faz sentido pra gateway on-device.
- `BotsScreen` virou **start destination** do `NavDisplay` (commit `e9b4897`); `ProfilesScreen` saiu de `DrawerSection.CONVERSE` (administração não é conversa). Converse agora: Bots → Bot DMs (PM1) → Chat.

---

## 3. PM7 — Pivô de navegação: bottom nav ⏳

> Decidido em 27/ago/2026. **Substitui** o PM2 PR2 (drawer swipeable E), que foi cancelado.
> Branch `feat/bottom-nav-bot-mode`, PR por abrir. Referência visual: [docs/mockups/bot-mode-redesign.html](mockups/bot-mode-redesign.html).

### 3.1 Por que o drawer (E) morreu

O item E foi adiado duas vezes pelo mesmo motivo — **gesture ownership**: o `ModalNavigationDrawer` raiz já
possui a borda esquerda, e um segundo drawer de bots teria de arbitrar o gesto com ele (opção (a)) ou sequestrar
o menu dentro do `ChatScreen` (opção (b)). O bottom nav **elimina** a disputa em vez de arbitrá-la: os bots
ganham uma âncora permanente na barra e o drawer raiz continua sendo o único dono da borda.

O `BotSwitcherSheet` da Fase 3 (chip no título do chat) continua vivo e continua sendo o caminho rápido de
troca **dentro** do chat — a barra não o substitui, porque a barra não aparece no `ChatScreen`.

### 3.2 O que foi implementado

**Commit 1 — casca de navegação** (`HermesBottomBar.kt`)
- 3 abas: **Bots** → `BotsScreen`, **Activity** → `ActivityScreen`, **More** → abre o drawer raiz.
  "More" **não é destino**: `BottomNavTab.key = null` e o clique chama `openDrawer()`. As ~24 telas ficam
  exatamente onde estavam.
- Duas regras puras, unit-testadas sem emulador (`BottomNavTest`):
  - `BottomNav.isVisibleOn(screen)` — a barra vive em telas top-level. **Esconde no `ChatScreen`** (o composer
    fica com a altura toda) e some nas drill-downs, que não estão em `ScreenRegistry.ALL_SCREENS` — a regra não
    apodrece quando a próxima sub-página nascer.
  - `BottomNav.selectedOn(screen)` — tudo que não é aba lê como **More**, que é exatamente o que a aba significa.
- A barra é hospedada **uma vez** pelo `Scaffold` do `MainNavigation`, não por tela: `HermesScaffold` fica
  intocado e a barra não some numa tela nova nem duplica numa antiga.
- **Chat vira destino, não aba:** saiu de `primaryScreens`, então empilha **sobre** a aba que o abriu. O
  `goBack` perdeu o caso especial "voltar do Chat abre History" — um pop normal devolve a aba. Chat só é raiz em
  cold start de notificação, e aí o fallback (Bots) é o lugar certo. Login também passou a cair em `BotsScreen`.
- `BotsScreen` e `ActivityScreen` saíram do drawer (`drawerSection = null`): são abas, uma entrada duplicada
  seria só uma segunda porta pro mesmo cômodo. `BotDmsScreen` **fica** no drawer como arquivo passivo (PM1).

**Commit 2 — feed de Activity** (`ui/activity/`)
- `ActivityItem.kt` — núcleo puro: `botActivity()` (fan-out de um bot → linhas), `routineActivity()`,
  `mergeActivity()`, `bucketOf()` (Hoje/Ontem/Anteriores/Sem data, no fuso do **viewer**) e `parseTimestamp()`
  (epoch numérico ou ISO-8601). 22 testes unitários.
- `ActivityViewModel` — mesma forma de carga do roster e do Bot DMs: profiles + active em paralelo, scan por bot
  do chat canônico (`limit=20&role=user&order=latest`), **mais uma** chamada de cron. **2N+3 requests**, teto de
  12 bots concorrentes. `refreshOnChange(setOf(SESSIONS, CRON))` — um coletor só, um guard só.
- **Política de falha:** só `getProfiles` é fatal. Bot que falha vai pra `unscannedBots`; cron que falha liga
  `routinesUnavailable`. Ambos aparecem como rodapé na tela — feed parcial que se declara bate tela de erro.

### 3.3 Desvios deliberados do mockup

O mockup pede linhas como *"writer finished a task"* e *"data flagged an anomaly"*. **Não implementadas de
propósito:** a API expõe mensagens e execuções de cron, não intenção. Um feed que adivinha semântica a partir do
texto da mensagem erra com confiança no primeiro bot sarcástico. Os três `ActivityKind` existentes são o que o
gateway de facto reporta:

| Kind | Fonte | Linha |
|---|---|---|
| `BOT_DM` | prefixo `Message from 🤖 X (@x):` no chat canônico (PM1) | "Hermes messaged research" |
| `USER_PROMPT` | turno `role=user` mais recente que **não** é DM | "You messaged coder" |
| `ROUTINE_RUN` | `last_run_at` / `last_run_status` do cron | "nightly-test ran" / "… failed" |

Outros limites, declarados em vez de escondidos:
- Só o chat **canônico** de cada bot é escaneado (`O(bots)`, não `O(sessions)`) — atividade em threads
  não-canônicas ou fora da janela de 20 turnos não lista. É uma visão de "o que está rolando", não auditoria.
- Sem `role=assistant` na varredura: "o bot respondeu" custaria uma segunda página por bot. Ficou de fora do
  primeiro corte.
- Máx. 5 DMs por bot no feed e teto de 60 linhas — um par de bots tagarela não pode empurrar os outros pra fora.

### 3.4 Validação

Build verde (`compileDebugKotlin`, `compileDebugAndroidTestKotlin`), **1218 testes unitários** verdes, ktlint
limpo. Instrumentados (`ActivityFeedListTest`, 4 casos com `now` fixo) compilam mas **não rodaram local** — não
há emulador nesta máquina; quem valida é o job `instrumented-tests` do CI.

### 3.5 Aberto / próximo

- Abrir o PR e deixar o CI rodar os instrumentados.
- **Não testado contra o gateway vivo** (100.101.230.70:9119) — o feed precisa de um smoke test com bots reais,
  sobretudo o formato de `last_run_at` do cron, que `parseTimestamp()` cobre em duas formas mas não em todas.
- Reavaliar `BotDmsScreen`: com o Activity agregando DMs, ele vira redundante. Fica no drawer como arquivo até
  o feed provar que cobre o caso.


## 4. Roadmap (PM3-PM6) — sem ação imediata

Documentado pra referência, não pra execução próxima. Prioridade reavaliada a cada merge.

### PM3 — Group chats (2-6 bots)
Sala compartilhada. Mensagem dispara até 3 rodadas seriais; @menções escopam quem responde; bots se puxam com `@name` e te escalam com `@user` (badge "needs you"); caps 10 msgs/send e 3 rounds; cada bot mantém sessão `Group: <nome>` persistente; salas espelhadas nos gateways conectados.

**Estado atual:** fundação cobre os blocos individuais. Falta: modelo de room (1:N generaliza o `BotChatRegistry`), UI da sala, orquestração serial de rounds, criação/gestão.

**Arquivos prováveis:** extensão do `BotChatRegistry` (1:N), novo `data/session/GroupRoomCoordinator.kt`, novo `ui/group/GroupRoomScreen.kt` + ViewModel, composer com @autocomplete.

**Estimativa:** ~2-3 semanas · 2-3 PRs. Risco médio-alto — orquestração multi-sessão é território novo no mobile. Fatiável em a→b→c→d.

### PM4 — @mention autocomplete no composer
- Desktop: `@` abre autocomplete validado contra roster vivo; renomear bot atualiza tag; email/`@` desconhecido passa intacto.
- **Escopo mobile:** autocomplete sobre roster (nomes + títulos), inserção de token `@nome`, sem resolução cross-device no primeiro corte.
- **Dependência:** faz mais sentido DEPOIS de PM1 (handoff precisa de destino) ou PM3 (mentions em sala). O composer já está contextual (PM2 B), então o autocomplete encaixa direto.
- **Reavaliar depois do PM2 PR2** — sem ele, "Bot DMs" do PM1 vira só arquivo de mensagens; com o autocomplete, vira inbox real.
- **Estimativa:** ~1-2 dias standalone.

### PM5 — Multi-source roster (bots de outros gateways)
Desktop agrega bots de múltiplas conexões (local + SSH + cloud) num roster só, com handles `@name-device`.

- **Relevância pro Tupay hoje:** BAIXA (1 gateway).
- **Relevância futura:** se espalhar bots entre Oracle A1 e outra máquina.
- **Decisão:** adiar até haver segundo gateway.

### PM6 — Avatares gerados por IA
Desktop usa `image.generate` RPC pra criar avatares. Backend não expõe campo de avatar no `ProfileInfo`; exigiria mudança server-side OU cache local do blob gerado.

- Cosmético; adiar.

---

## 5. Ordem sugerida

```
AGORA:   PM7 (bottom nav)       — código pronto, falta PR + smoke test no gateway vivo
↓ depois: PM4 (autocomplete)     ~1-2d   — reavalia se BotDmsScreen ainda faz sentido como "arquivo"
↓ depois: PM3 (group chat)       ~2-3 sem — fatiável a→b→c→d
cancelado: PM2 PR2 (drawer E)
adiados:   PM5, PM6
```

PM4 depende funcionalmente de PM1/PM3 (precisa de contexto onde mencionar). PM3 é o único grande e pode ser fatiado.

---

## 6. Riscos e ressalvas ativas

1. ✅ **`SessionInfo.preview` = primeiro user prompt** (validado 24/ago/2026). Last-message real vem de `GET /api/sessions/{id}/messages?limit=1&role=user`. Custo do fan-out do roster: 2N+1 requests — aceitável para N < ~15.
2. **Pin como marcador durável** colide com pin manual do utilizador em `SessionsScreen` (`togglePin`). Despinar o canonical perde só o *fallback* cross-device — o mapa local continua. Aceitável no MVP; vale um aviso no `SessionsScreen` numa iteração futura.
3. **`ChatViewModel` é o ponto quente** (3.352 linhas, máquina de generations/resume). Alteração do MVP é deliberadamente um só `if` reutilizando o caminho `initialSessionId` já existente. Qualquer refactor maior da lógica de resume fica fora deste escopo.
4. **Avatar = monograma determinístico** (4 slots de paleta do `colorScheme`). Para avatares reais, o backend não expõe campo — exigiria mudança server-side (PM6).
5. ✅ **Gesture ownership — resolvido por eliminação** (PM7): o bottom nav dispensou o segundo drawer, então não há dois donos da borda esquerda. O risco que adiou o item E duas vezes deixou de existir em vez de ser arbitrado.
5b. **Custo do feed de Activity: 2N+3 requests** por carga (roster 2N+1 + cron). Mesma ordem do roster e do Bot DMs, mas as três telas podem recarregar juntas num burst de `sessions.changed`. Aceitável para N < ~15; acima disso vale um cache partilhado do scan por bot.
6. **`session.create` não persiste até o 1º prompt** (`sessionHasServerPresence`) — pin do canonical continua tendo de ser diferido (Fase 0 → 2). Vale pra qualquer novo caller que criar sessões.
7. **Confirmado fora de escopo até segunda ordem:** routines pane completo (PM2 A cobriu o caso "tool com side effect", mas um painel de rotinas é outra conversa), group chat, multi-source roster, avatares IA.

**Confirmado dentro do escopo de qualquer fase:** nenhuma das features acima cria estruturas que bloqueiem as outras. O `BotChatRegistry` é um mapa 1:1 que se generaliza pra 1:N (PM3) sem migração destrutiva. PM1 já criou o terreno pros badges e o composer com @autocomplete (PM4).

---

## 7. Compatibilidade

Todas as features pós-MVP são **client-side sobre APIs existentes** — nenhuma exige mudança no gateway Hermes v0.20.x. O protocolo bot-to-bot e as salas espelhadas já são server-side no upstream; o mobile apenas consome. Syncs futuros do upstream Hy4ri não conflitam (código novo em arquivos novos), e o Desktop permanece fonte da verdade pra qualquer estado compartilhado.
