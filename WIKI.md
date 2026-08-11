# Devil Bargen Wiki

This is a basic reference for the contract system in **Devil Bargen**. Contracts are written in normal English inside
a signed book. The server asks the selected parser to translate the writing into typed clauses, shows the resulting
interpretation, and executes only those typed clauses.

`I`, `me`, and `my` normally mean the **dealmaker**. `You` and `your` normally mean the **acceptor**.

## Creating a deal

1. Use Devil Bargen in **Contract** mode to receive a Book and Quill.
2. Write the complete deal and sign the book.
3. Hold the signed book and use Contract again.
4. Read the server-added interpretation carefully.
5. Give the book to the other player. They use its acceptance link to sign the deal.

The dealmaker and acceptor must both be online when assets are checked. The acceptor must possess the exact
server-bound contract book. A player cannot normally accept their own deal.

Writing clearer terms produces more predictable contracts. Namespaced IDs such as `minecraft:diamond`,
`minecraft:the_end`, or `tensura:great_sage` are more reliable than ambiguous names.

## Parties

| Party | Meaning |
|---|---|
| `DEALMAKER` | The player who created the contract. |
| `ACCEPTOR` | The player who accepts the contract. |
| `ANY_PLAYER` | Any player. Valid only for supported event conditions such as chat, never as an asset source or destination. |

## Triggers

| Trigger | Behavior |
|---|---|
| `ON_ACCEPTANCE` | Runs when the contract is accepted. |
| `ON_RECURRING_DUE` | Repeats after `periodTicks`. Any typed action may recur; the minimum period is 20 ticks. |
| `ON_CONDITION_MET` | Runs when its condition matches without marking the deal breached. The deal stays active unless an `END_DEAL` clause in that occurrence completes it. |
| `ON_BREACH` | Runs as an expressly written breach/default consequence and marks the deal breached. |

Useful time conversions:

- 20 ticks = 1 second.
- 1,200 ticks = 1 minute.
- 6,000 ticks = 5 minutes.
- 24,000 ticks = 1 Minecraft day.

## Conditions

### Boolean logic

One action may have up to eight condition terms:

- `ALL` means **AND**: every term must be true.
- `ANY` means **OR**: at least one term must be true.
- Any individual term may be negated for **NOT**.
- Combining `ALL`/`ANY` with negated terms also expresses NAND, NOR, “unless,” and De Morgan forms.

The conditions remain attached to one action. For example, `if you are crouching AND I say duck, you die` is one
death action with an `ALL` group; it is not two independent death actions.

### General

| Condition | Meaning |
|---|---|
| `ALWAYS` | No additional requirement. Common for acceptance and recurring actions. |

### Items and equipment

| Condition | Meaning |
|---|---|
| `PARTY_HAS_ITEM` | The selected party has at least the requested number of an item or item tag. |
| `PARTY_HAS_ITEM_IN_SLOT` | A selected slot contains the requested item/tag and quantity. |
| `PARTY_HOLDS_ANY_ITEM` | A selected slot is not empty. |
| `ITEM_ENTERED_INVENTORY` | The matching item/tag count increased by the requested amount after acceptance or the last execution. |

Item tags use `#namespace:path`, such as a live vanilla/mod/data-pack tag. Unknown IDs and nonexistent tags are
rejected instead of guessed.

Use `NOT PARTY_HAS_ITEM` for “lacks,” “does not have,” or “has fewer than.”

Supported slot names:

- `MAIN_HAND` — the currently selected hotbar slot.
- `OFF_HAND` or `OFFHAND`.
- `HOTBAR_1` through `HOTBAR_9`.
- `ARMOR_HEAD`, `ARMOR_CHEST`, `ARMOR_LEGS`, and `ARMOR_FEET`.

### Statistics and resources

| Condition | Meaning |
|---|---|
| `PARTY_STAT_AT_LEAST` | A Minecraft custom statistic has reached the requested total. |
| `PARTY_STAT_INCREASED` | A custom statistic increased by the requested amount since its saved baseline. |
| `PARTY_RESOURCE_AT_LEAST` | EP, magicule, or aura is at least the requested amount. |
| `PARTY_RESOURCE_INCREASED` | EP, magicule, or aura increased by the requested amount since its baseline. |

Examples of custom statistics include `minecraft:bell_ring` and `minecraft:walk_one_cm`. Resource names are `ep`,
`magicule`, and `aura`. In contract wording, **MP means magicule**.

### Environment and later deals

| Condition | Meaning |
|---|---|
| `PARTY_WEATHER_IS` | The selected party is in clear weather, rain, or thunder. |
| `PARTY_TIME_OF_DAY_IS` | The selected party is in the named phase: `dawn`, `day`, `dusk`, or `night`. |
| `PARTY_LIGHT_LEVEL_AT_LEAST` | The combined local light level at the party is at least 0 through 15. |
| `PARTY_ACCEPTED_OTHER_DEAL` | The selected party accepted a later deal from the current dealmaker, current acceptor, another player, or an exact player UUID. |

Environment conditions are edge-triggered for `ON_CONDITION_MET`: entering the matching weather, phase, or light state
fires once and re-arms after leaving it. Use light level `15` for “higher than 14.” Later-deal acceptance only observes
successfully accepted contracts after this one; it never treats pending/rejected books or this contract’s own acceptance
as a match. For “if you accept another deal from someone who is not me,” use
`OTHER_THAN_CURRENT_DEALMAKER`.

### Chat, combat, death, and skill use

| Condition | Meaning |
|---|---|
| `CHAT_MESSAGE_CONTAINS` | The selected speaker's chat contains a literal phrase, ignoring case. |
| `PARTY_HARMED_PARTY` | One contract party successfully harmed the other. Nullified/zero damage does not count. |
| `PARTY_DIES` | The selected party died. |
| `PARTY_USES_SKILL` | The selected party activated one exact registered skill ID. |
| `PARTY_USES_SKILL_CATEGORY` | The selected party activated category `any`, `magic`, or `battlewill`. |

### Current player state

| Condition | Meaning |
|---|---|
| `PARTY_IS_CROUCHING` | The selected party is currently crouching. |
| `PARTY_IS_SPRINTING` | The selected party is currently sprinting. |
| `PARTY_IS_SWIMMING` | The selected party is currently swimming. |
| `PARTY_IS_ON_GROUND` | The selected party is currently on the ground. |

### Distance, coordinates, and dimensions

| Condition | Meaning |
|---|---|
| `PARTY_WITHIN_DISTANCE_OF_PARTY` | The selected party moved within the requested radius of the other party. |
| `PARTY_IN_DIMENSION` | The selected party entered the named dimension. |
| `PARTY_CHANGED_DIMENSION` | The selected party changed dimensions. It may optionally require a particular destination. |
| `PARTY_WITHIN_COORDINATE_RADIUS` | The party moved within a radius of X/Y/Z in a named dimension. |

Spatial conditions are edge-triggered: they fire when state changes from not matching to matching, rather than every
second the player remains inside/outside. Different dimensions count as outside coordinate regions.

Use `NOT PARTY_WITHIN_DISTANCE_OF_PARTY`, `NOT PARTY_IN_DIMENSION`, or
`NOT PARTY_WITHIN_COORDINATE_RADIUS` for outside/leaving forms. Entering a coordinate region is simply the positive
`PARTY_WITHIN_COORDINATE_RADIUS` edge.

Coordinate axes are independent:

- A numeric coordinate compares that axis.
- `~` ignores that axis completely.
- `0,0` is shorthand for `0,~,0`.
- Any combination works, including `~,0,0`, `0,0,~`, `~,~,0`, `~,0,~`, `0,~,~`, and `~,~,~`.
- The interpretation prints ignored axes as `~` and shows the compared axis set, such as `axes=XZ`.

## Deal abilities/actions

### Skills

| Action | Effect |
|---|---|
| `TRANSFER_ALL_SKILLS_IN_CATEGORY` | Transfers every skill in category `unique`, `ultimate`, `magic`, or `battlewill`. |
| `TRANSFER_SKILL` | Transfers one exact registered skill and its instance data. |
| `SHARE_SKILL` | Gives a separate cost-free copy while the owner keeps theirs. The copy starts at zero mastery and cannot gain mastery. |

A transfer fails safely if the source does not own the skill or the recipient already owns a conflicting copy.

### Attributes

| Action | Effect |
|---|---|
| `TRANSFER_ATTRIBUTE_PERCENT` | Moves a percentage of an attribute's current base value. |
| `TRANSFER_ATTRIBUTE_AMOUNT` | Moves an exact base-value amount. |
| `REVOKE_ATTRIBUTE_GRANTS` | On breach, removes only an attribute grant recorded by this same deal and restores it to its source. |

Registered vanilla or modded attribute IDs may be used. A transfer is rejected if it would exceed the attribute's
allowed bounds.

### Items and inventory

| Action | Effect |
|---|---|
| `TRANSFER_ITEM_AMOUNT` | Transfers an exact number of an item. |
| `TRANSFER_ALL_MATCHING_ITEMS` | Transfers as many matching items as the recipient can hold. |
| `TRANSFER_INVENTORY_SLOT` | Transfers the complete stack in a selected slot. |

For a repeating payment, use `TRANSFER_ITEM_AMOUNT` with `ON_RECURRING_DUE`, just like any other recurring action.

Transferred stacks preserve names, enchantments, damage, custom data, and mod components. Items are not deleted or
dropped when the recipient lacks capacity; the action fails or retries safely.

### Tensura resources

| Action | Effect |
|---|---|
| `TRANSFER_RESOURCE_AMOUNT` | Transfers an exact amount of `ep`, `magicule`, or `aura`. |
| `TRANSFER_RESOURCE_PERCENT` | Transfers a percentage of current `ep`, `magicule`, or `aura`. |
| `DRAIN_RESOURCE_AMOUNT` / `DRAIN_RESOURCE_PERCENT` | Removes a fixed amount or percentage of EP, MP/magicule, or aura from one signer and transfers it to the other. |
| `DESTROY_RESOURCE_AMOUNT` / `DESTROY_RESOURCE_PERCENT` | Removes a fixed amount or percentage of EP, MP/magicule, or aura without crediting either signer. |
| `REDIRECT_RESOURCE_GAIN_PERCENT` | Redirects a percentage of newly gained EP, magicule, or aura. Pre-existing resources and losses are not redirected. |

Resource redirection prevents feedback loops. The source can never transfer more than their live balance.

### Damage, death, souls, and ending

| Action | Effect |
|---|---|
| `REDIRECT_DAMAGE_PERCENT` | Redirects a percentage of incoming damage from one party to the other while the deal is active. |
| `DEAL_DAMAGE_AMOUNT` | Deals direct generic damage to the selected source party after any reversible transfers succeed. |
| `SET_ON_FIRE_SECONDS` | Sets the selected source party on fire for a whole number of seconds after any reversible transfers succeed. |
| `KILL_PLAYER` | Kills the selected source through the normal server death path. It may be immediate, conditional, breach-based, or recurring. |
| `FORFEIT_SOUL` | Gives the source party's soul to the destination party. It may be immediate or an explicitly written payment-default consequence. |
| `END_DEAL` | Marks the deal `COMPLETED` after any sibling mutations in the same occurrence succeed. Completed transfers and grants stay; ongoing redirects and conditions stop. Use a condition, breach, or recurring trigger—not acceptance. |

Multiple damage redirects process the remaining damage in deal order. Redirected damage keeps its original damage
source and has recursion protection.

`END_DEAL` is the typed form of wording such as “the deal ends,” “this contract is over,” or “our bargain ends.”
It is not a breach. Manual dealmaker severing still uses `/devilbargen sever` and marks the deal `SEVERED`.

## Example contracts

These are natural-language examples, not rigid commands:

- `You will give me all your Unique skills, and I will give you 20 health.`
- `Every in-game day you will pay me 10 minecraft:diamond. If you cannot, your soul is forfeited to me.`
- `If you enter the End, you die.`
- `If I die, you die.`
- `If you hit me, you die. If I hit you, the deal ends.`
- `If you ring a bell, transfer every minecraft:diamond you possess to me.`
- `Any item you hold in your main hand goes to me.`
- `Every five minutes, your main-hand item goes to me.`
- `Every five minutes, you die.`
- `If you move outside 500 blocks of 0 64 0 in minecraft:overworld, you die.`
- `You take 50% of all damage intended for me.`
- `I receive 25% of all magicule you gain.`
- `If you use tensura:great_sage, your soul is forfeited to me.`
- `If you walk into light level higher than 14, you burn for 5 seconds.`
- `If you accept another deal from someone who is not me, you die and your soul is forfeited to me.`
- `Every day, drain 25% of your MP to me.`

Always read the appended server interpretation. If ambiguous wording is translated into an unexpected but valid
typed outcome, the interpretation—not the writer's unstated intention—is what the server enforces.

## Soul authority

Whoever possesses the one valid claimed-soul item possesses that soul. The item may be carried or placed in the
27-slot Soul Storage. Duplicate live soul representations fail closed and authorize nothing.

| Command | Effect |
|---|---|
| `/devilbargen soul damage <player>` | Invokes lethal soul-authority damage. |
| `/devilbargen soul take_ep <player> <amount>` | Takes up to the requested EP from the soul owner. |
| `/devilbargen soul summon <player>` | Teleports the soul owner to the holder. |
| `/devilbargen soul inventory <player>` | Opens a custody-checked view of the soul owner's inventory. |
| `/devilbargen soul name <player> <name>` | Changes the soul owner's Tensura name. |
| `/devilbargen soul alignment <player> <alignment>` | Sets `default`, `majin`, `holy`, or `chaos` alignment. |
| `/devilbargen soul forcedeal <player>` / `all` | While holding your pending contract book, forces that typed deal onto one owned soul, or every online player whose soul you uniquely possess. Ongoing clauses stay active. |

Soul actions are logged and briefly rate-limited. Inventory access stops immediately when custody is lost.

## Other commands

| Command | Effect |
|---|---|
| `/devilbargen accept <deal-id>` | Accepts a pending deal when holding its bound book. |
| `/devilbargen sever <deal-id>` | Lets the dealmaker stop an active deal. Completed transfers are not reversed. |
| `/devilbargen deals` | Lists deals involving the player. |
| `/devilbargen debug <deal-id>` | Shows compiled clauses and runtime baselines to an involved party. |
| `/devilbargen storage` | Opens Soul Storage. |

## Execution and safety rules

- Every action is server-side and typed. Contract text and AI output are never executed as commands or code.
- Live ownership, balances, skill state, attribute bounds, parties, deal state, and inventory capacity are rechecked.
- A multi-action occurrence is transactional. If a step fails, completed mutations are rolled back.
- A missing later asset does not invent a punishment. Only an explicitly written typed default/breach consequence runs.
- Recurring clocks continue while players are offline, then evaluate when both are online.
- Existing transfers remain after a deal is severed or completed by `END_DEAL` unless a typed clause expressly revokes them.
- Commands, operator/admin access, permissions, gamemode changes, bans, whitelist changes, console access, arbitrary NBT,
  and arbitrary code remain forbidden.

## Not currently available

Race transfer/removal, selected skill-mode transfer, subordinate ownership transfer, persistent forced deals that follow
soul custody, and generic command execution are not advertised. Unsupported wording should be rejected rather than
silently approximated.
