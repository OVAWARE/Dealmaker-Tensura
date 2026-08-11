# Devil Bargen

See [WIKI.md](WIKI.md) for the complete basic list of triggers, conditions, deal actions, soul powers, commands, and examples.

A NeoForge 1.21.1 addon for Tensura 2.0.1.0. The mod adds one ultimate skill, **Devil Bargen**, with three modes:

- **Contract** gives the user a Book and Quill. Activate it again while holding a signed book; the contract pages stay at the front and the server appends its interpretation plus a clickable acceptance page. Give that book to another player to let them accept it.
- **Soul Storage** opens a 27-slot storage that accepts only UUID-bound claimed souls.
- **End Deal** lists the dealmaker's deals and gives active deals a server-side sever action.

The spelling `Bargen` is retained from the requested skill name.

## Implemented contract language

The first implemented deal vocabulary is intentionally typed and server-validated:

- `You will give me all your uniques, in return I will give you 5% of my strength stat`
- `Every in game day you will pay 10 diamonds, if you do not have them your soul is forfeit`

Transfers are not direction-locked: either signer can transfer their Unique skills or any registered attribute (including `minecraft:generic.max_health`) to the other. Attribute transfers support both percentages and exact base-stat amounts. A soul clause with `periodTicks = 0` is an immediate voluntary soul transfer; a positive period marks a recurring-payment default.

The AI compiles typed conditions for inventory quantities/slots/acquisition, custom statistics, chat, death/harm,
skill use, party proximity, coordinate regions, dimensions, weather, day phases, local light levels, later-deal
acceptance, and EP/magicule/aura thresholds or increases. Spatial and environmental predicates are edge-triggered
and resource/stat/inventory baselines persist across saves. Clauses run on acceptance, independent recurring
schedules, ordinary condition matches, or an explicit breach. A breach can revoke only the attribute amount that the
same deal previously granted, then apply only its stated typed consequences.

Namespaced item IDs can replace the allowlisted common item names in recurring payments. Unsupported wording is rejected; it is never guessed.

## Security model

Parsing produces inert `DealClause` data. A clause type allowlist and field validator run after every parser, including any future AI parser. The server then validates ownership, quantities, party identity, deal state, and soul custody immediately before mutation. Model output can never contain commands, Java, NBT, permissions, registry mutation, or arbitrary capability names.

The dealmaker cannot accept their own deal. Transactions reserve aggregate assets and destination space, preserve
item/skill components, and roll back inventories, skills, attributes, resources, and soul storage if any step fails.
A deal cannot transfer an asset that the live source does not possess. Operator status, permission changes, commands,
gamemode, bans, whitelist changes, console access, and arbitrary NBT are rejected at the policy boundary.

Saved pending/active deals from data version 1 are marked `INVALIDATED_LEGACY`; completed history is preserved.
`/devilbargen debug <deal-id>` shows involved parties the compiled clauses and persisted runtime baselines.

## Soul authority commands

All require the target's UUID-bound soul in the caller's inventory or Soul Storage:

- `/devilbargen soul damage <player>`
- `/devilbargen soul take_ep <player> <amount>`
- `/devilbargen soul summon <player>`
- `/devilbargen soul inventory <player>`
- `/devilbargen soul name <player> <name>`
- `/devilbargen soul alignment <player> <alignment>`
- `/devilbargen soul forcedeal <player|all>` while holding a pending contract book you authored (single owned soul, or every online owned soul)

Custody is fail-closed: exactly one live soul representation may exist server-wide. Duplicate copies authorize
nothing, soul actions are audited/rate-limited, and remote inventory access is revoked immediately when custody is lost.

If a player is killed by someone whose soul they possess, that killer's soul is removed from both their inventory and storage.

## AI providers

Provider settings are generated in `config/tensura/dealmaker/server.toml`:

```toml
aiProvider = "LOCAL" # Change to GOOGLE_AI_STUDIO or OPENROUTER
googleAiStudioApiKey = "" # Optional; use an environment variable instead when possible
openRouterApiKey = ""    # Optional; use an environment variable instead when possible
googleModel = "gemini-3.5-flash"
googleStructuredOutput = false
logMalformedAiResponse = true
openRouterModel = "google/gemini-3.5-flash"
requestTimeoutSeconds = 90
connectTimeoutSeconds = 10
maxOutputTokens = 512
temperature = 0.0
requestCooldownSeconds = 5
```

Both API keys can be entered directly in this server-only config. Do not commit, share, or distribute that file. Environment variables are the preferred alternative:

```text
GOOGLE_AI_STUDIO_API_KEY=...   # GEMINI_API_KEY is also accepted
OPENROUTER_API_KEY=...
```

JVM properties `-Ddealmaker.googleApiKey=...` and `-Ddealmaker.openRouterApiKey=...` are supported when environment variables are unavailable. Environment variables are preferred because command-line arguments may be visible to other users on the host.

After editing this file while the server is running, use `/reload`. Devil Bargen reloads its server configuration as part of Minecraft's reload lifecycle; subsequent contract requests use the new values.

Both integrations are asynchronous and never block the Minecraft server thread. Only the signed contract text, static instructions, and the clause JSON schema leave the server. Player UUIDs, inventories, skills, API keys, and other game state are not included.

The model response remains untrusted. It is size-limited, decoded into the four known clause kinds, checked by `DealPolicy`, shown to the recipient as a server interpretation, and validated again against live assets on acceptance. A malformed response, timeout, partial interpretation, unsupported direction, duplicate clause, unknown item, or provider error creates no deal.

Provider failures are recorded in `minecraft/logs/latest.log` with the provider, model, HTTP status/API error message, or connection failure type. Keys and contract text are never written to the log.

## Build

This workspace uses the compatible Tensura/ManasCore jars from `../Tensurarune/libs`:

```text
../Tensurarune/gradlew -p . clean build
```

## Deliberately not advertised

Race removal/transfer, selected skill-mode transfer, entity subordinate ownership, persistent custody-following forced
deals, and resistance-nullifying contract damage remain rejected. The installed APIs do not yet expose rollback-safe
source removal or a stable player-level ownership model for each of these. They will not be approximated or added to
the AI vocabulary until their adapters and game tests are complete.
