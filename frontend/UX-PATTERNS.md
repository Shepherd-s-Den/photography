# Frontend UX patterns

Read when implementing or reviewing frontend UI changes. This reference owns cross-screen visual and content intent; [Contributing](../CONTRIBUTING.md) and the [frontend quality checklist](../.agents/skills/sdd/checklists/code-quality-frontend.md) own engineering and interaction-verification obligations.

## Visual language

Keep photographs and album content primary. Editing chrome is compact and quiet: inherited typography, restrained framing and clear grouping distinguish navigation from mutation without competing with the canvas.

Extend the existing visual language before introducing another one. [Application styles](src/app/app.css) own shared presentation; [workspace styles](src/features/albums/workspace/workspace.css) show its denser editing context. Use their current styles as the authority for values and responsive treatment.

## Wording and actions

Use sentence case for UI headings, controls and hints. Preserve proper names, saved user content and catalog labels rather than applying case conversion to them.

Name actions by their effect in the existing domain vocabulary. Present authoritative saved names instead of reconstructing titles from identifiers or other metadata.

Compact toolbar icons use the established thin-stroke language, with hover text and equivalent accessible names. Keep related actions visibly grouped and their presentation consistent when selection changes. The [workspace action toolbar](src/features/albums/workspace/ChapterOutline.tsx) is the existing reference, not a catalog of icons to duplicate.

## Interaction continuity

Follow the existing dialog and feedback patterns when extending an interaction. [Album dialogs](src/features/albums/dialog/AlbumDialog.tsx) and [spread confirmation](src/features/albums/workspace/SpreadConfirmation.tsx) provide concrete precedents; focus, pending-state and disabled-feedback obligations remain with the contribution conventions and quality checklist.

Preserve the distinction between navigation, editing and viewing modes. Keyboard shortcuts belong to the interaction that owns them and must coexist with text entry and native controls; consult the current owning component before introducing or reassigning one.

Feature-specific placement, dimensions, shortcut assignments and exact copy stay with their source and acceptance tests. Carry their established behavior forward when changing adjacent UI.
