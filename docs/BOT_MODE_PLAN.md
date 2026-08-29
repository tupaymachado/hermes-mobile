# BOT MODE — SPEC

> Repo `tupaymachado/hermes-mobile` (fork de `Hy4ri/hermes-mobile`). Gateway Hermes v0.20.5 @ `100.101.230.70:9119` (tailnet).
> Atualizado 29/ago/2026 — PM7 mergeado (PR #9).

**Formato.** Seções fixas, itens endereçáveis por `§`. Commit, PR e issue citam `§V3` em vez de repetir o contexto.
Convenções: `!` = must/não · `→` = leva a · `∴` = portanto · `∀` = todo. Status: `x` feito · `~` em curso · `.` a fazer · `✗` cancelado · `-` adiado.

---

## §G GOAL

Um **bot** = um `ProfileInfo` server-side (≠ `ConnectionProfile` local). Cada bot tem um chat canônico durável;
trocar de bot = trocar o profile ativo. Tudo vive sobre `AuthManager.activeProfileId`.

---

## §V INVARIANTS

Numeradas, testáveis. Violação = bug, não escolha de estilo.

- **V1** `ProfileSwitchCoordinator.switchProfile()` = único caminho de troca (REST flip → persist → emit `switched: SharedFlow<String>` → re-dial). Estender, nunca contornar. O chat **só** re-escopa por re-dial do socket: os métodos de `WsMethods.PROFILE_SCOPED_METHODS` carregam `params.profile` do dial corrente.
- **V2** `?profile=` explícito vence o `ProfileScopeInterceptor` ∴ ler o bot alheio **sem** trocar o ativo.
- **V3** `session.create` ! persiste linha no servidor até o 1º prompt (`sessionHasServerPresence`) ∴ `PATCH pinned=true` em sessão nova = 404 ∴ pin do canonical **diferido** (`BotChatRegistry.flushPendingPin`). Vale ∀ novo caller que crie sessão.
- **V4** `SessionInfo.preview` = **primeiro** user prompt, ! a última mensagem (validado 24/ago/2026). Last-message real: `GET /api/sessions/{id}/messages?limit=1&role=user`.
- **V5** Monograma do `BotAvatar` e ring do ativo ! viram semantics — identidade vem do nome adjacente.
- **V6** Falha de um bot ≠ tela partida. Degrada a linha, nomeia o que falhou, mantém selecionável. "Não consegui olhar" ! pode ler como "não há nada".
- **V7** Drawer gesture é screen-owned (`HermesScaffold(drawerGesturesEnabled)`). O bottom nav ! toca nisso: o drawer raiz segue único dono da borda esquerda.
- **V8** `role=user` ⊅ turnos humanos: markers (`model_switch`, `personality_switch`, `auto_continue`) viajam como user (#904) ∴ filtrar `display_kind` antes de tratar como prompt.
- **V9** Chave de linha de lista ! pode repetir — `items(key=)` **lança**. Ids escopados por bot + `distinctBy` no merge.
- **V10** `ChatViewModel` = ponto quente (3.4k linhas, máquina de generations/resume). Mudança pendura em caminho existente; refactor de resume fica fora de escopo.
- **V11** Toda string nova nasce em `en` + `ko` + `zh`.
- **V12** Sessão de origem MÁQUINA ! é conversa. Cada execução de cron abre a sua própria sessão (`cron_<job>_<stamp>`, `source = "cron"`) cujo 1º turno user é o preâmbulo injetado `[IMPORTANT: You are running as a scheduled task…]` ∴ toda varredura por bot escolhe a sessão via `newestConversation()`, nunca `firstOrNull()`. Mesma família de V8: turno que é *tecnicamente* `role=user` sem ser o utilizador a falar.

---

## §D DECISIONS

- **D1** `BotChatRegistry` — objeto puro (sem Compose/Android). Resolução: mapa local → sessão `pinned` → `null` → `adopt()` + `flushPendingPin()`. `invalidate()` = self-heal sobre `ActiveSessionHolder`/`recoverGoneSession` (4007/404) — `recoverGoneSession` só **invalida**, ! re-adota. Mapa 1:1 que generaliza pra 1:N (§P3) sem migração destrutiva.
- **D2** Persistência em `ServerStoreState.botChatSessions: Map<String,String>` — `ignoreUnknownKeys` ∴ sem migração.
- **D3** Touchpoint do chat canônico = `ChatViewModel`, collector de `switched` → `resetSessionState(null)` → `handleGatewayReady()` (usa `initialSessionId` se `currentSessionId == null`, senão `createNewSession()`) — o caminho "abrir sessão específica após ready" já existia pras notificações. Handoff = `consumePendingBotSession()` → `PendingBotChat(profile, sessionId?)`, carrega **profile + sessionId**, ! só o sessionId: sem o profile, "abrir bot pela 1ª vez" não consegue adotar. `switchProfile(isBotContext)` separa switch normal de switch-de-roster; switch normal **limpa** handoff armado (não ignora).
- **D4** Fan-out (roster, Bot DMs, Activity): profiles + active em paralelo, depois por bot a sessão mais nova **que seja conversa** (`getSessions(limit=CONVERSATION_PROBE_LIMIT, order=recent, profile=<bot>)` → `newestConversation()`, §V12) + uma página de turnos (`limit=20&role=user&order=latest`). **2N+1** (Activity: 2N+3, +cron), teto **12** concorrentes. `O(bots)`, ! `O(sessions)` — é o que torna a tela pagável.
- **D5** Refresh por `refreshOnChange`. Roster = **duas** assinaturas (`sessions.changed` last-message · `gateway.changed` presence) com guard separado; Activity = **um** coletor sobre `{SESSIONS, CRON}`. Backend que emite só um subconjunto degrada pra ele; nenhum → pull-to-refresh.
- **D6** `BotSwitcherSheet` usa VM de escopo **Activity**: o switch sobrevive ao dismiss. VM de sheet seria cancelada mid-switch → servidor flipado e app não re-homed, exatamente o split-brain que V1 existe pra evitar. `selectBot(onSwitched)` só dispara depois do servidor aceitar ∴ falha mantém a sheet aberta com o toast, e tocar no bot já ativo reporta em vez de fechar num no-op.
- **D7** Avatar = monograma determinístico, 4 slots do `colorScheme` (guard `checkColorLiterals`). Backend não expõe campo de avatar (§P6).
- **D8** Navegação = bottom nav de 3 abas (Bots · Activity · More). **More ! é destino** (`key = null`) → abre o drawer raiz, e as ~24 telas ficam onde estavam. Barra hospedada **uma vez** pelo `Scaffold` do `MainNavigation` ∴ `HermesScaffold` intocado, barra não some nem duplica. **Chat = destino, ! aba**: saiu de `primaryScreens`, empilha sobre a aba que o abriu, back devolve essa aba. `BotsScreen`/`ActivityScreen` saíram do drawer (`drawerSection = null`): são abas, entrada duplicada seria 2ª porta pro mesmo cômodo. `BottomNav.isVisibleOn`/`selectedOn` são puras; drill-downs caem fora por não estarem em `ScreenRegistry.ALL_SCREENS` ∴ a regra não apodrece quando nascer a próxima sub-página.
- **D9** Activity tem **3 kinds só** — `BOT_DM` (prefixo `Message from 🤖 X (@x):`), `USER_PROMPT` (turno user mais novo que ! é DM), `ROUTINE_RUN` (`last_run_at`/`last_run_status`). O mockup pedia "finished a task" / "flagged an anomaly": **recusado**, exigiria inferir intenção do texto e erra com confiança no primeiro bot sarcástico.
- **D10** Política de falha do Activity: só `getProfiles` é fatal. Bot que falha → `unscannedBots`; cron que falha → `routinesUnavailable`; ambos viram rodapé visível (§V6).
- **D12** @mention (§P4) mora em `MentionPolicy` (puro) + `MentionViewModel` (uma `GET /api/profiles`, sem o fan-out 2N+1 do roster) — **não** em `ChatViewModel` (§V10) nem no `BotsViewModel`. O gatilho é o **caret**, ! o início do texto como no menu de slash: uma menção pode ser escrita ou editada em qualquer ponto da mensagem. O `@` tem de ABRIR palavra ∴ `tupay@gmail.com` nunca abre lista. Roster que não carrega = sem sugestões e o `@` segue texto puro — autocomplete é acelerador, ! pode barrar o envio.
- **D11** `ServerEndpoint.DEFAULT_BASE_URL = http://100.101.230.70:9119/` (Tailscale, HTTP puro dentro do túnel). O default upstream `https://127.0.0.1:9119/` só serve gateway on-device.

---

## §L LIMITS

Declarados, não escondidos — cada um é uma escolha de custo, não um esquecimento.

- **L1** Só o chat **canônico** de cada bot é escaneado, janela de **20** turnos ∴ atividade fora disso não lista. É "o que está rolando", ! auditoria.
- **L2** Sem `role=assistant` no scan: "o bot respondeu" custaria uma 2ª página por bot.
- **L3** Máx **5** DMs por bot, teto **60** linhas no feed — um par tagarela ! empurra os outros pra fora.
- **L4** `ToolActionCard`: o transcript REST ! traz `toolName`/`toolStatus` por design (#771) ∴ cards só pra tool rows vistas ao vivo ou restauradas do Room. Ausência nunca vira card errado.

---

## §S SHIPPED

| id | status | o quê | onde |
|---|---|---|---|
| S0 | x | `BotChatRegistry` + `?profile=` no `getSessions` (§D1 §D2 §V2 §V3) | `feat/bot-mode-registry` |
| S1 | x | Roster: `BotsViewModel` + `BotRosterRow` + `BotAvatar` (§D4 §D7 §V6) | `feat/bot-mode-roster` |
| S2 | x | Chat canônico por profile; handoff via `consumePendingBotSession()` (§D3) | `feat/bot-mode-canonical-chat` |
| S3 | x | `BotSwitcherSheet` no título do chat (§D6) | `feat/bot-mode-quick-switch` · PR #4 |
| S4 | x | Presence ao vivo (`gateway.changed`), 3º estado `lastMessageUnavailable`, a11y sujeito+estado, i18n (§D5 §V5 §V6 §V11) | `chore/bot-mode-polish` |
| S5 | x | PM1 — DMs bot-a-bot legíveis: badge de autor no chat, tela agregada, badge no roster | `feat/bot-dms-visible` · PR #6 |
| S6 | x | PM2 A/B/D — `ToolActionCard` (§L4), composer contextual, avatar saturado + ring (§V5) | `feat/bot-mode-visual-polish` · PR #7 |
| S7 | x | `BotsScreen` vira start destination; `ProfilesScreen` sai de CONVERSE | PR #8 |
| S8 | x | PM7 — bottom nav (`ui/common/HermesBottomBar.kt`) + feed de Activity (`ui/activity/`) (§D8 §D9 §D10 §L1-L3) | `feat/bottom-nav-bot-mode` · PR #9 |

| S9 | x | P4 — @mention autocomplete no composer (§D12) | `feat/mention-autocomplete` |

**Validação de S8 (CI verde, 29/ago/2026):** unit, **instrumentados**, ktlint, Android Lint, release-compile, CodeQL.

---

## §B BUGS

Log de backprop: cada linha é um bug + a invariante que impede a recorrência.

| id | data | causa | ∴ fix | cita |
|---|---|---|---|---|
| B1 | 27/ago | ids `dm:$sessionId:$idx` iguais entre bots quando um gateway legado ignora `?profile=` e devolve a mesma sessão pra todos → chave duplicada **crasha** a lista | id escopado por bot + `distinctBy` no merge; feed degrada, não morre | V9 |
| B2 | 27/ago | markers em `role=user` lidos como prompt → linha falsa "You messaged X" **e** expulsão do prompt real (só o mais novo não-DM sobrevive) | `activityTurns()` filtra `display_kind` | V8 |
| B3 | 27/ago | `EmptyState` é `fillMaxSize` dentro da `Column` → comia a tela e empurrava o rodapé de degradação pra fora; todos os bots falhando renderizava "Nothing yet" | `weight(1f)` em cada braço do `when` | V6 |
| B4 | 27/ago | `last_run_at` vem ISO-8601 com **offset** (`-03:00`); `Instant.parse` só aceita offset ≥ JDK 12 e o `java.time` da API 26 tem semântica Java 8 → passava no teste (JDK 21) e devolvia null **no device** | `OffsetDateTime` → fallback `LocalDateTime`; stamp ilegível vira linha **sem data**, não linha descartada | — |
| B6 | 29/ago | Varredura por bot pegava `sessions.firstOrNull()` com `limit=1` → no gateway vivo as 2 sessões mais recentes eram execuções de cron ∴ feed renderizava **"You messaged default"** sobre o preâmbulo injetado, roster mostrava-o como última mensagem, e a mesma execução aparecia 2× (uma verdadeira como `ROUTINE_RUN`, uma falsa como prompt) | `newestConversation()` partilhado pelas 3 varreduras (roster, Bot DMs, Activity) | V12 |
| B7 | 29/ago | Roster pedia `limit=1` de `role=user` **sem** filtrar `display_kind` → trocar o modelo de um bot fazia a linha dele ler "Switched model to opus". O Activity já filtrava (B2); o roster não | `MARKER_PROBE_LIMIT` turnos + `lastOrNull { display_kind.isNullOrBlank() }` | V8 |
| B5 | 27/ago | id de DM usava o índice da janela → a mesma mensagem trocava de chave a cada turno novo (churn de keys) | id de mensagem do gateway (#859), fallback estável `timestamp+seq` | V9 |

---

## §R RISKS

- **R1** Pin durável colide com o pin manual do utilizador (`SessionsScreen.togglePin`). Despinar o canonical perde só o *fallback* cross-device — o mapa local segue (§D1). Aceitável; vale um aviso no `SessionsScreen`.
- **R2** 2N+3 por carga do Activity, e roster/DMs/Activity podem recarregar juntos num burst de `sessions.changed`. OK pra N < ~15; acima disso, cache partilhado do scan por bot.
- **R3** ✅ **Exercitado contra o gateway vivo em 29/ago/2026** (via CLI oficial — a API HTTP exige credencial que a sessão não tinha). Conferidos: 3 bots reais (`default` running · `coder`/`secretaria` stopped) ∴ N=3 e 2N+3=9 requests por carga; `last_run_at = 2026-08-29T08:02:20.196986-03:00` (offset, µs, sem `Z`) exatamente como §B4 previu; `last_run_status = ok`; marcador `pinned` existe. **Achou B6 e B7.** Falta ainda: percorrer a UI real num device, e o caminho HTTP com token (ver §R5).
- **R5** O teste de regressão do §B4 vive em `app/src/test` (JVM/JDK 21) — a mesma camada que deixou o B4 passar. `OffsetDateTime.parse` aceita offset desde o Java 8 ∴ o fix está certo por construção, mas **nenhuma camada de teste aqui roda a semântica de `java.time` da API 26**. Guard durável seria um check de CI proibindo `Instant.parse` sobre stamps do gateway.
- **R4** `BotDmsScreen` ficou redundante com o Activity. Segue no drawer como arquivo passivo até o feed provar que cobre o caso.

---

## §P PLAN

| id | status | o quê | custo |
|---|---|---|---|
| P7 | x | Bottom nav + feed de Activity | mergeado (PR #9) |
| P4 | x | @mention autocomplete no composer (§D12, §S9). 1º corte: nomes + descrições do roster, insere `@nome `, sem resolução cross-device | feito |
| P3 | . | Group chats 2-6 bots: sala partilhada, ≤3 rodadas seriais, @menções escopam quem responde, `@user` escala pra ti (badge "needs you"), caps 10 msgs/send, sessão `Group: <nome>` por bot. Falta: modelo de room (1:N de §D1), `GroupRoomCoordinator`, `GroupRoomScreen` + VM | 2-3 sem · fatiável a→b→c→d |
| P5 | - | Multi-source roster (bots de outros gateways, handles `@name-device`) — adiado até existir 2º gateway | — |
| P6 | - | Avatares por IA (`image.generate`) — backend sem campo de avatar, exigiria mudança server-side; cosmético | — |
| PE | ✗ | Drawer lateral swipeable — **cancelado** por §D8: o bottom nav elimina a disputa de gesto em vez de arbitrá-la. O `BotSwitcherSheet` (S3) segue sendo a troca rápida **dentro** do chat, onde a barra não aparece | — |

**Ordem:** P3 é o que resta com trabalho desenhado — o único grande, fatiável a→b→c→d.

---

## §X OUT OF SCOPE

Painel de rotinas completo (§L4 cobriu só "tool com side effect"); refactor da máquina de resume (§V10); qualquer
mudança server-side.

**Compatibilidade:** tudo é client-side sobre API existente — nenhum item exige mudar o gateway v0.20.x. O
protocolo bot-a-bot e as salas espelhadas já são server-side no upstream; o mobile consome. Syncs do upstream
Hy4ri não conflitam (código novo em arquivos novos); o Desktop segue fonte da verdade pra estado partilhado.
