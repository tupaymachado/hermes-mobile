# Bot Mode — Plano & Histórico de Implementação

> Repo: `tupaymachado/hermes-mobile` (fork de `hermes-agent/hermes-mobile`)
> Validação inicial: 24/ago/2026 contra gateway Hermes v0.20.5 vivo na tailnet (100.101.230.70:9119).
> Atualizado em: 27/ago/2026, após merge do PR #7 (PM2 PR1 — A/B/D).

## TL;DR

- **MVP fechado**: Fases 0-4 (5 PRs) — registry → roster → canonical chat → quick switcher → polish. Tudo mergeado em `main`.
- **Pós-MVP em curso**:
  - ✅ PM1 — bot-to-bot DMs legíveis (PR #6)
  - ✅ PM2 PR1 — polish visual A/B/D (Spacek-inspired) (PR #7)
  - ⏳ **PM2 PR2 — drawer lateral swipeable (E)**, adiado por risco de gesture ownership. É a próxima frente.
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
| _próximo_ | **PM2 PR2** | _a abrir_ | **Drawer lateral swipeable (E)** |
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

**Invariante de arquitetura (AGENTS.md, ainda vale):** drawer gesture é **screen-owned** (`HermesScaffold(drawerGesturesEnabled = ...)`). O PM2 PR2 (drawer de bots) vai ter que declarar quais telas podem ativar o gesto de bots sem brigar com o drawer raiz do menu.

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

## 3. Pendente (próxima frente)

### 3.1 PM2 PR2 — Drawer lateral swipeable (E) ⏳

> Adiado do PR #7 por risco de gesture ownership. É o **único item pendente** com trabalho já desenhado.

**Inspiração:** Spacek. Complementa (não substitui) o `BotSwitcherSheet` da Fase 3, que continua sendo o caminho rápido pelo título.

**Comportamento:**
- **Abrir:** swipe da borda esquerda pra direita (gesto padrão Material 3) **ou** tap no avatar/bot-name no header do chat.
- **Fechar:** tap no scrim, swipe pra esquerda, ou tap no bot selecionado (que também navega pro chat dele — duplo papel).
- **Conteúdo:** lista de bots igual à `BotRosterRow`, com o ativo destacado pelo ring (D do PR1).
- **Persistente sobre o chat:** o usuário volta exatamente onde estava.

**Escopo técnico:**
- Componente `BotSwitcherDrawer` (`ModalNavigationDrawer` ou `AnchoredDraggable` do Material 3).
- Integrar com `DrawerGestureController` (já existe — AGENTS.md exige screen-owned): declarar quais telas podem ativar o drawer de bots (Converse: Bots, Bot DMs, Chat → `true`; sub-pages → `false`).
- Tap no bot = switch via `BotsViewModel.selectBot()` + fecha drawer.
- Avatar no header do chat vira o trigger secundário.
- **Reuso, não duplicação:** mesmo `BotRosterItem` + `BotsViewModel` da `BotSwitcherSheet`.

**Caveat arquitetural — decidir antes de codar:**
- `ModalNavigationDrawer` raiz do Hermes já existe pro menu principal. Ou:
  - **(a) Segundo drawer empilhado** — mais complexo, dois gestures disputando a borda esquerda;
  - **(b) Substituir `NavIcon.Menu` no header do Chat por `NavIcon.Bots`** — drawer de bots vira o menu do chat, drawer raiz só aparece em outras telas.
- Recomendo **(b)** no `ChatScreen` (escopo confinado, sem interferência no resto). Decisão a confirmar antes de codar.

**Arquivos prováveis:** novo `ui/bots/components/BotSwitcherDrawer.kt`, mudanças em `ChatScreen` (header trigger), `HermesScaffold` (gesture opt-in por tela — provavelmente já é opt-in por `drawerGesturesEnabled`, validar), `BotsViewModel` (reuso).

**Estimativa:** ~3 dias · 1 PR. Risco médio (gesture ownership é delicado — tem que coexistir com o menu principal sem brigar pelo gesto da borda).

**Validação esperada:** build verde, ktlint limpo, testes instrumentados cobrindo (1) abrir por gesto, (2) abrir por tap no avatar, (3) fechar por scrim, (4) fechar por tap no bot selecionado, (5) coexistência com menu raiz fora do `ChatScreen`.

### 3.2 Decisão aberta antes do PR2
- **(a)** segundo drawer empilhado vs **(b)** substituir menu no header do ChatScreen. Recomendo (b).

---

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
AGORA:   PM2 PR2 (drawer E)     ~3d
↓ depois: PM4 (autocomplete)     ~1-2d   — reavalia se PM1 ainda faz sentido como "arquivo"
↓ depois: PM3 (group chat)       ~2-3 sem — fatiável a→b→c→d
adiados: PM5, PM6
```

PM4 depende funcionalmente de PM1/PM3 (precisa de contexto onde mencionar). PM3 é o único grande e pode ser fatiado.

---

## 6. Riscos e ressalvas ativas

1. ✅ **`SessionInfo.preview` = primeiro user prompt** (validado 24/ago/2026). Last-message real vem de `GET /api/sessions/{id}/messages?limit=1&role=user`. Custo do fan-out do roster: 2N+1 requests — aceitável para N < ~15.
2. **Pin como marcador durável** colide com pin manual do utilizador em `SessionsScreen` (`togglePin`). Despinar o canonical perde só o *fallback* cross-device — o mapa local continua. Aceitável no MVP; vale um aviso no `SessionsScreen` numa iteração futura.
3. **`ChatViewModel` é o ponto quente** (3.352 linhas, máquina de generations/resume). Alteração do MVP é deliberadamente um só `if` reutilizando o caminho `initialSessionId` já existente. Qualquer refactor maior da lógica de resume fica fora deste escopo.
4. **Avatar = monograma determinístico** (4 slots de paleta do `colorScheme`). Para avatares reais, o backend não expõe campo — exigiria mudança server-side (PM6).
5. **Gesture ownership** (PM2 PR2): o `ModalNavigationDrawer` raiz do Hermes já existe pro menu principal. A coexistência com o drawer de bots é o ponto de risco do PR2. Decidir **(a)** drawer empilhado vs **(b)** substituir menu no ChatScreen antes de codar.
6. **`session.create` não persiste até o 1º prompt** (`sessionHasServerPresence`) — pin do canonical continua tendo de ser diferido (Fase 0 → 2). Vale pra qualquer novo caller que criar sessões.
7. **Confirmado fora de escopo até segunda ordem:** routines pane completo (PM2 A cobriu o caso "tool com side effect", mas um painel de rotinas é outra conversa), group chat, multi-source roster, avatares IA.

**Confirmado dentro do escopo de qualquer fase:** nenhuma das features acima cria estruturas que bloqueiem as outras. O `BotChatRegistry` é um mapa 1:1 que se generaliza pra 1:N (PM3) sem migração destrutiva. PM1 já criou o terreno pros badges e o composer com @autocomplete (PM4).

---

## 7. Compatibilidade

Todas as features pós-MVP são **client-side sobre APIs existentes** — nenhuma exige mudança no gateway Hermes v0.20.x. O protocolo bot-to-bot e as salas espelhadas já são server-side no upstream; o mobile apenas consome. Syncs futuros do upstream Hy4ri não conflitam (código novo em arquivos novos), e o Desktop permanece fonte da verdade pra qualquer estado compartilhado.
