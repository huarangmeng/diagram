## Problem

Mermaid timeline cards can render the event label and period caption on top of each other.

- Actual behavior: a card such as `2020 : Born` places `Born` and `2020` in nearly the same vertical area.
- Expected behavior: the event label and period caption are stacked with enough spacing inside the card.
- Reproduction: render a Mermaid timeline with a title and at least one period/event pair, such as a history timeline with `2020 : Born` and `2024 : Grew up`.

## Root Cause Analysis

The timeline layout reserved item height using a single combined label, while the renderer displayed timeline cards as two separate visual text rows: the event label and the period caption. Because the layout did not reserve caption height, short cards could not contain both rows.

A related contributing factor was fixed at the same time: timeline slots used a fixed height even when a slot contained multiple stacked cards. Dense slots could therefore extend beyond their track bounds and visually collide with following content.

## TDD Fix Plan

1. **RED**: Write a rendering test for a timeline card with an event label and period caption, asserting the two text origins are vertically separated and the card height can contain both rows.
   **GREEN**: Measure event label and period caption separately during layout, and reserve enough card height for both rows.

2. **RED**: Write or extend a timeline layout/rendering test for multiple events in one period, asserting the slot grows to contain all stacked cards.
   **GREEN**: Compute slot height from the required heights of its cards instead of using only the fixed minimum slot height.

**REFACTOR**: Keep all text measurement in the layout layer and leave rendering as a pure consumer of resolved geometry.

## Acceptance Criteria

- [ ] Timeline cards render event labels and period captions without overlap.
- [ ] Timeline slots expand to contain stacked cards.
- [ ] One-shot and chunked streaming outputs remain deterministic.
- [ ] All new tests pass.
- [ ] Existing timeline tests still pass.
