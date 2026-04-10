# SLP Meal Selection Android App

This app is a native Android tablet workflow for school meal service, with separate **Kitchen** and **Child-facing** modes.

## What the app does
- Lets a user log in as either kitchen or child tablet.
- Kitchen side can start service, view prep totals, and mark active meals as served.
- Child side can start meal time, select a class, select a child, and submit a check-in.
- After child confirmation, the child tablet shows a waiting overlay until kitchen marks the meal as served.

## Current UX behavior
- Full-screen landscape tablet layout with branded header/footer.
- Child check-in uses large touch-first buttons for class and child selection.
- Child header bar remains orange throughout child-facing mode.
- Login/setup screen resizes for keyboard (`adjustResize`) so username/password/continue remain accessible.

## Run in Android Studio
1. Open this folder in Android Studio.
2. Let Gradle sync.
3. Run on an Android tablet (or landscape emulator).

## Notes
- Sample meal and pupil data is currently in-memory in `MainActivity.kt`.
- Next step can be replacing this with an API or persistent storage.
