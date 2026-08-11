# Devil Bargen contract test matrix

This is a manual integration suite for every **active canonical** action and condition. It intentionally excludes
deprecated enum aliases: those are persistence/decoder compatibility paths, not new contracts to author.

Run these on a disposable server with two players:

- **Maker** creates each book.
- **Acceptor** accepts it.
- Both players must be online for acceptance and for tick/event conditions.
- Give both players enough inventory space, the named items/resources, and registered Tensura skills before each case.
- Read the server-added interpretation before accepting: the expected canonical kind/condition below is the assertion.

For conditions with a consequence, verify both the event and the consequence. Reset or use a fresh pair of players
after a death, soul transfer, direct damage, or fire test.

## Acceptance and transfer actions

1. `I give you 10 minecraft:diamond.`  
   Expect `TRANSFER_ITEM_AMOUNT`, `ON_ACCEPTANCE`, `ALWAYS`; Acceptor receives 10 diamonds.

2. `I give you every minecraft:diamond I have.`  
   Expect `TRANSFER_ALL_MATCHING_ITEMS`; Maker's diamonds move until Acceptor inventory capacity is reached.

3. `I give you the item in my main hand.`  
   Expect `TRANSFER_INVENTORY_SLOT` with `MAIN_HAND`; exact stack components are preserved.

4. `I give you 100 EP.`  
   Expect `TRANSFER_RESOURCE_AMOUNT` with `ep`; verify both EP balances.

5. `I give you 25% of my aura.`  
   Expect `TRANSFER_RESOURCE_PERCENT` with `aura`; verify 25% of acceptance-time aura moves.

6. `You drain 100 MP to me.`  
   Expect `DRAIN_RESOURCE_AMOUNT` with `magicule`; Acceptor loses and Maker gains 100 magicule.

7. `You drain 25% of your MP to me.`  
   Expect `DRAIN_RESOURCE_PERCENT` with `magicule`; Acceptor loses and Maker gains the acceptance-time percentage.

8. `You lose 100 aura into the void.`  
   Expect `DESTROY_RESOURCE_AMOUNT` with `aura`; Acceptor loses aura and Maker gains none.

9. `You lose 25% of your EP into the void.`  
   Expect `DESTROY_RESOURCE_PERCENT` with `ep`; Acceptor loses EP and Maker gains none.

10. `I give you 10% of my minecraft:generic.max_health.`  
    Expect `TRANSFER_ATTRIBUTE_PERCENT`; verify both base max-health values.

11. `I give you 4 minecraft:generic.attack_damage.`  
    Expect `TRANSFER_ATTRIBUTE_AMOUNT`; verify both base attack-damage values.

12. `I give you tensura:great_sage.`  
    Expect `TRANSFER_SKILL`; Maker no longer owns the exact skill and Acceptor owns its instance.

13. `I share tensura:great_sage with you.`  
    Expect `SHARE_SKILL`; Maker retains it and Acceptor gets the tagged, zero-mastery, non-masterable copy.

14. `I give you all my unique skills.`  
    Expect `TRANSFER_ALL_SKILLS_IN_CATEGORY` with `unique`; prepare at least two unique skills.

15. `I voluntarily give you my soul.`  
    Expect `FORFEIT_SOUL`, `ON_ACCEPTANCE`; verify the unique soul is held/stored by Acceptor.

## Persistent actions and scheduled actions

16. `You receive 25% of all magicule I gain.`  
    Expect `REDIRECT_RESOURCE_GAIN_PERCENT` with `magicule`; increase Maker's magicule and verify the redirected
    portion reaches Acceptor once without redirect feedback.

17. `You take 50% of all damage intended for me.`  
    Expect `REDIRECT_DAMAGE_PERCENT`; damage Maker and verify half reaches Acceptor with the original source.

18. `Every five minutes, you take 3 damage.`  
    Expect `DEAL_DAMAGE_AMOUNT`, `ON_RECURRING_DUE`, `periodTicks=6000`; wait five minutes and verify direct damage.

19. `Every five minutes, you burn for 5 seconds.`  
    Expect `SET_ON_FIRE_SECONDS`, `ON_RECURRING_DUE`, `periodTicks=6000`; wait five minutes and verify fire.

20. `Every five minutes, you die.`  
    Expect `KILL_PLAYER`, `ON_RECURRING_DUE`, `periodTicks=6000`; verify the normal death path.

21. `Every in-game day you pay me 10 minecraft:diamond. If you default, your soul is forfeited to me.`  
    Expect recurring `TRANSFER_ITEM_AMOUNT` plus the matching `FORFEIT_SOUL` default companion. Run once with diamonds,
    then once without diamonds.

## Inventory and statistic conditions

22. `If you have 3 minecraft:diamond, you take 2 damage.`  
    Expect `PARTY_HAS_ITEM`; give Acceptor three diamonds.

23. `If you hold a minecraft:diamond in your main hand, you burn for 3 seconds.`  
    Expect `PARTY_HAS_ITEM_IN_SLOT` with `MAIN_HAND`; switch the selected hotbar item.

24. `If you hold any item in your off hand, you take 2 damage.`  
    Expect `PARTY_HOLDS_ANY_ITEM` with `OFF_HAND`; place/remove any stack.

25. `If a minecraft:diamond enters your inventory, transfer every minecraft:diamond you own to me.`  
    Expect `ITEM_ENTERED_INVENTORY`; add diamonds after acceptance and verify all matching diamonds move.

26. `If you have rung a bell at least once, you take 2 damage.`  
    Expect `PARTY_STAT_AT_LEAST` with `minecraft:bell_ring`; ring before acceptance, then wait for the condition poll.

27. `If you ring a bell, you take 2 damage.`  
    Expect `PARTY_STAT_INCREASED` with `minecraft:bell_ring`; ring after acceptance.

28. `If you have at least 100 magicule, you take 2 damage.`  
    Expect `PARTY_RESOURCE_AT_LEAST` with `magicule`; set Acceptor's Magicule above and below the threshold.

29. `If your aura increases by 50, you take 2 damage.`  
    Expect `PARTY_RESOURCE_INCREASED` with `aura`; increase aura after acceptance.

## Chat, combat, death, and skill conditions

30. `If you say "contract test", you take 2 damage.`  
    Expect `CHAT_MESSAGE_CONTAINS`; send the exact phrase from Acceptor.

31. `If you harm me, you take 2 damage.`  
    Expect `PARTY_HARMED_PARTY`; Acceptor must deal positive damage to Maker.

31b. `If you hit me, you die. If I hit you, the deal ends.`  
    Expect `KILL_PLAYER` when Acceptor harms Maker, and `END_DEAL` when Maker harms Acceptor; verify the deal
    becomes `COMPLETED` and ongoing clauses stop after Maker lands a hit.

32. `If I die, you take 2 damage.`  
    Expect `PARTY_DIES`; kill Maker through normal gameplay.

33. `If you use tensura:great_sage, you take 2 damage.`  
    Expect `PARTY_USES_SKILL`; activate that exact skill as Acceptor.

34. `If you use any magic, you take 2 damage.`  
    Expect `PARTY_USES_SKILL_CATEGORY` with `magic`; activate a registered magic skill.

## Player-state, position, and dimension conditions

35. `If you crouch, you take 2 damage.`  
    Expect `PARTY_IS_CROUCHING`; crouch after acceptance.

36. `If you sprint, you take 2 damage.`  
    Expect `PARTY_IS_SPRINTING`; sprint after acceptance.

37. `If you swim, you take 2 damage.`  
    Expect `PARTY_IS_SWIMMING`; enter water and swim.

38. `If you are on the ground, you take 2 damage.`  
    Expect `PARTY_IS_ON_GROUND`; land after acceptance.

39. `If you get within 5 blocks of me, you take 2 damage.`  
    Expect `PARTY_WITHIN_DISTANCE_OF_PARTY`; enter the radius, leave it, and enter again to verify rearming.

40. `If you enter minecraft:the_nether, you take 2 damage.`  
    Expect `PARTY_IN_DIMENSION`; use a Nether portal.

41. `If you change dimension, you take 2 damage.`  
    Expect `PARTY_CHANGED_DIMENSION`; use any portal.

42. `If you enter within 5 blocks of 0 64 0 in minecraft:overworld, you take 2 damage.`  
    Expect `PARTY_WITHIN_COORDINATE_RADIUS`; enter, leave, and re-enter the region.

## Environmental and deal-acceptance conditions

43. `If it rains where you are, you take 2 damage.`  
    Expect `PARTY_WEATHER_IS` with `rain`; use weather controls on the test server, then change away and back.

44. `If it thunders where you are, you take 2 damage.`  
    Expect `PARTY_WEATHER_IS` with `thunder`; change weather away and back to verify rearming.

45. `If it is dawn, you take 2 damage.`  
    Expect `PARTY_TIME_OF_DAY_IS` with `dawn`; move the world time into and out of dawn.

46. `If it is night, you take 2 damage.`  
    Expect `PARTY_TIME_OF_DAY_IS` with `night`; move the world time into and out of night.

47. `If you walk into light level higher than 14, you burn for 5 seconds.`  
    Expect `PARTY_LIGHT_LEVEL_AT_LEAST` with amount `15` and `SET_ON_FIRE_SECONDS`; enter bright light, leave, then
    re-enter.

48. **Create and accept this first:** `If you accept another deal from someone who is not me, you die and your soul is forfeited to me.`  
    Expect `PARTY_ACCEPTED_OTHER_DEAL` with `OTHER_THAN_CURRENT_DEALMAKER`, `KILL_PLAYER`, and `FORFEIT_SOUL` on
    breach. Have a third player create and Acceptor accept a separate valid deal. Confirm death and soul custody.

49. **Create and accept this first:** `If you accept another deal from me, you take 2 damage.`  
    Expect `PARTY_ACCEPTED_OTHER_DEAL` with `CURRENT_DEALMAKER`. Have Maker create a second valid deal and Acceptor
    accept it; verify direct damage. A third-party deal must not match.

50. **Create and accept this first:** `If you accept another deal from this exact player, you burn for 3 seconds.`  
    Use the other dealmaker's UUID as the counterparty constraint in the server interpretation. Verify only that UUID
    triggers `PARTY_ACCEPTED_OTHER_DEAL`.

## Breach and revocation regression

51. `I give you 10 minecraft:generic.max_health. If you harm me, the health I granted you is revoked.`  
    Expect acceptance `TRANSFER_ATTRIBUTE_AMOUNT`, then breach `REVOKE_ATTRIBUTE_GRANTS` with
    `PARTY_HARMED_PARTY`. Verify only this deal's recorded grant is returned.

52. `If you enter minecraft:the_end, you die.`  
    Expect `PARTY_IN_DIMENSION` with `KILL_PLAYER`; this is the minimal high-impact edge-condition regression.

53. `If you are crouching AND hold a minecraft:diamond, you take 2 damage.`  
    Expect one `DEAL_DAMAGE_AMOUNT` clause with `ConditionLogic.ALL`, `PARTY_IS_CROUCHING`, and
    `PARTY_HAS_ITEM`. Repeat with either term false, then use `OR` and `unless` wording to verify boolean grouping
    and negation.
