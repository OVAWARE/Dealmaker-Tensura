# Devil Bargen remaining implementation plan

This document records the remaining work before further feature changes are made. It is intentionally explicit about
what exists, what is incomplete, and the server-side mechanism each feature needs.

## Current foundation

- Contracts are compiled into typed, allowlisted clauses. AI text is never executed as commands.
- Google AI Studio and OpenRouter parsing, signed-book proposals, acceptance, storage, soul custody, deal logging,
  chat/harm/death/stat/skill-use events, direct resource transfers, skill transfer, and non-masterable skill sharing
  already exist.
- Several newly added clauses/conditions are not yet fully covered by an in-game integration test suite. Every change
  below must receive a parsing test and an in-game/server integration test before being treated as complete.

## 1. Registry lookup flow for modded names

Problem: dumping registries into every model request is wasteful, but static/fuzzy name matching cannot correctly
distinguish modded content such as separate walking/running counters or similarly named abilities.

Plan:

1. Add a read-only `search_registry` model tool with `kind` (`item`, `stat`, `attribute`, `skill`, `dimension`) and
   `query`.
2. Search only the live server registries, return at most 20 exact IDs plus display names/category metadata.
3. Run at most four tool calls per contract and cache results for that contract request.
4. Send tool results back to the same model, then require the final typed contract JSON.
5. Keep all final IDs validated locally. The tool cannot execute commands, inspect inventories, change data, or expose
   API keys.
6. Implement both provider adapters, with a safe JSON preflight fallback for models that reject function declarations.

## 2. Unified event-condition model

Problem: every event currently has bespoke dispatch code. This caused earlier bugs where an `ON_CONDITION_MET` harm
clause was compiled correctly but ignored by a dispatcher that only checked `ON_BREACH`.

Plan:

1. Introduce a single `DealEventContext` containing event type, actor, target, optional skill ID, chat text, position,
   dimension, item slot, and resource delta.
2. Route chat, harm, death, skill activation, item/equipment changes, dimension changes, movement, and resource gains
   through one `executeMatchingEventClauses` method.
3. Require every condition to declare whether it is a normal `ON_CONDITION_MET` automation or an actual `ON_BREACH`.
4. Preserve a deal when normal automation executes; mark it breached only for explicit breach clauses.
5. Log every matched condition, evaluated values, clauses run, and reason when execution is skipped.

## 3. Position, proximity, coordinates, and dimensions

Required wording:

- "if you get within X blocks of me"
- "if you are outside X blocks of me"
- "if you enter/leave/change dimension"
- "if you are within X blocks of X Y Z in dimension D"

Plan:

1. Add typed conditions for party-to-party radius, coordinate radius, dimension equals/not-equals, and dimension change.
2. Store dimension IDs and coordinate/radius data in dedicated typed condition fields; do not overload free-form strings.
3. Track previous dimension and previous in/out-of-region state per active deal so entry/exit fires once rather than
   every second.
4. Tick position conditions at a configurable interval, default one second, and use squared distance calculations.
5. Treat different dimensions as outside a party/coordinate radius.
6. Support both normal automation and explicit breach behavior for every position condition.

## 4. Inventory, held items, equipment, and item acquisition

Required wording:

- "any item you hold, I get"
- main hand, offhand, specific hotbar slot, helmet/chestplate/leggings/boots
- "if an item enters your inventory"
- quantity checks such as 64 diamonds

Plan:

1. Extend slot identifiers with dynamic `MAIN_HAND` (currently selected hotbar slot), `OFF_HAND`, and armor slots.
2. Add `PARTY_HAS_ITEM_IN_SLOT`, `PARTY_HOLDS_ANY_ITEM`, and `ITEM_ENTERED_INVENTORY` conditions.
3. Add transfer clauses for the selected slot and all equipped/held items, preserving components/NBT/enchantments.
4. Track inventory snapshots per deal to distinguish "already has item" from "item entered inventory".
5. Add item predicates for exact item, tag, any item, count, and full-stack quantities.
6. Make all-matching-item transfers move the maximum that fits, retry only when new matching inventory state appears,
   and never classify a successful transfer as a breach.

## 5. Resource gain redirection

Required wording:

- "your magicule gain is given to me"
- equivalent EP and aura forms
- percentages and conditional redirects

Plan:

1. Add persistent resource snapshots per active deal for EP, magicule, and aura.
2. On each resource observation, calculate only positive delta; never redirect pre-existing resource or losses.
3. Add `REDIRECT_RESOURCE_GAIN_PERCENT` clauses with resource type and percentage.
4. Subtract redirected amount from the gaining party and credit the recipient, respecting any known server-side caps.
5. Prevent feedback loops: a recipient's redirected gain must not itself count as a new gain for the same redirect chain.
6. Prefer native Tensura gain events if available; otherwise use tick snapshots with explicit logs of old value, new value,
   positive delta, redirected amount, and recipient.

## 6. Damage and death contracts

Existing pieces: player harm, player death, death penalty, and damage percentage redirection have been added.

Remaining work:

1. Add integration tests for 0%, 50%, and 100% redirect; verify damage source, armor/resistance behavior, and recursion
   protection.
2. Define stacking semantics for multiple redirects (ordered remainder, capped at 100%, logged).
3. Verify reciprocal death contracts cannot produce uncontrolled death-event loops.
4. Add explicit support for damage thresholds and damage-source filters if requested.

## 7. Skill sharing and transfers

Existing pieces: normal transfer and `SHARE_SKILL` are separate. Shared copies are tagged cost-free and blocked from
mastery through ManasCore's mastery callback.

Remaining work:

1. Persist share provenance (deal ID, owner UUID, recipient UUID, skill ID) so a share can be revoked/severed if a
   future contract clause requires it.
2. Verify all skill categories, modes, sub-instances, toggles, and cooldown state for both share and transfer.
3. Add tests proving normal transfers retain transfer semantics while shared copies start at zero mastery and cannot
   progress.
4. Verify direct storage operations never charge MP, EP, magicule, or soul energy; add explicit logs/tests around
   before/after resource values.

## 8. Soul authority

Existing pieces: custody requirement, remote damage, EP taking, summon, inventory opening, Tensura name, and alignment.

Remaining work:

1. Define additional soul powers as individual typed actions, never arbitrary commands.
2. Add audit logging for each soul action: holder, soul owner, action, result, and failure reason.
3. Add optional server-config permission toggles/rate limits for high-impact remote actions.
4. Ensure custody checks work identically for inventory-held and storage-held souls, including death/loss behavior.

## 9. AI/parser reliability and observability

Plan:

1. Keep raw sanitized malformed-model output logging enabled behind config.
2. Log proposed program, accepted program, event baseline, event match, execution, and execution skip.
3. Canonicalize harmless provider formatting aliases only when fields are semantically unused; never silently broaden an
   executable capability.
4. Add regression tests from every observed malformed provider response.
5. Add a `/devilbargen debug <deal-id>` view for owners that displays compiled clauses, state snapshots, and last event.

## Implementation order

1. Finish and test the unified event-condition dispatcher.
2. Add persistent deal state for inventory, region, dimension, and resource snapshots.
3. Implement slot/inventory acquisition conditions and resource-gain redirection.
4. Implement coordinate/dimension region conditions.
5. Add registry lookup tool loop and remove broad registry dumps.
6. Add integration tests and debug command for every event family.
