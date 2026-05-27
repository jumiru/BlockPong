# BlockPong Testing Workflow

This workflow keeps collision bugs reproducible and fixable.

## 1) Capture a bug in debug mode

Quick shortcut in app:

- 2 fingers: cycle slow-motion
- 3 fingers: single-step + log snapshot
- 4 fingers: create a full report, copy to clipboard, open share/email composer

When a freeze happens (for example `ball 6 on block`), capture:

- freeze reason text
- tick/update counter
- ball id
- ball `prev(x,y)` and `next(x,y)`
- ball `dx/dy`
- nearby block coordinates and values

Use one plain text note per bug report with this template:

```text
Title:
Expected:
Actual:
Freeze reason:
Update counter:
Ball id:
Prev pos (x,y):
Next pos (x,y):
Velocity (dx,dy):
Blocks near hit (x,y,type,value):
Repro steps:
```

## 2) Convert each bug to a deterministic unit test

Add tests in `app/src/test/java/com/jrgames/blockpong/GameBoardCollisionTest.java`.

Rules:

- Use `createTestBoard()` and `clearBoardForTests()`.
- Place only the minimal blocks needed for the bug.
- Create one ball with fixed start position and speed.
- Run one step via `stepBallOnceForTests(...)`.
- Assert both physics and game state (`dx/dy`, position bounds, block value changes).

## 3) Fix code with red-green-refactor

- Red: failing test reproduces bug.
- Green: smallest possible fix in `GameBoard`/`Ball`.
- Refactor: clean up and keep all collision tests green.

## 4) Run focused tests first

```powershell
Set-Location "C:\Users\juxe_\AndroidStudioProjects\BlockPong"
.\gradlew.bat testDebugUnitTest --tests "com.jrgames.blockpong.GameBoardCollisionTest" --tests "com.jrgames.blockpong.BallPhysicsTest"
```

## 5) Keep a regression list

Keep every fixed bug test in the suite permanently. Do not remove old failing cases.


