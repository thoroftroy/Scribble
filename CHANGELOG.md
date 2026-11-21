# Scribble changelog

## v0.7.1

- Each app's animated scene now has its own drifting symbols (maths signs in Abacus, notes in Echo, arrows in Wayfinder and so on), and the scene is a little clearer while text stays easy to read
- Animated backgrounds reworked after your report: they are drawn at a higher resolution and smoothly at 24 frames a second without slowing the app down, and no longer show horizontal lines

## v0.7.0

- Open an Obsidian vault in one tap: Scribble finds the folders on your phone that contain a vault and lists them
- Every app window now has the animated scene behind it, in the app's own colour (for example orange water in Abacus, green in Pulse, purple in Scribble), following your Aqua, Bliss or Super look and the real sunrise and sunset. Motion Reduced shows a still picture and Off turns it off
- Help buttons (?) explain the more confusing settings

## v0.6.2

- Fixed a gap above the keyboard in screens with a bottom tab bar: the tab bar now steps aside while you type, so you can see what you are writing

## v0.6.1

- Can open a file by path when another Wonderdesk app asks, so Ripple notepads edit straight in Scribble

## v0.6.0

- Open any folder as a vault from the top of the main screen: pick a folder, switch between recent ones, or open the whole phone as one tree. Sub-folders show as a tree like Obsidian
- The folder choice is no longer buried in Settings

## v0.5.21

- Tables now show as real tables while you edit, not just in preview. Tap a cell to type, tap the arrow on a heading to sort, align, add, move or delete columns, use the handle on a row to add, duplicate or delete it, and drag the handle to move the row. + Row and + Column are under the table
- The folder tree is now the main view: a clear Folders, Cards, List switch under the filters, every folder open at first, a button on each folder to add a note or a folder inside it

## v0.5.20

- New Insert menu in the editor: a table (you choose the rows and columns), a picture from your gallery (kept with the note and shown in preview), checklist item, heading, bulleted or numbered list, quote, code block, divider, note link, web link and the date and time

## v0.5.19

- A row of cursor and selection buttons sits above the keyboard: move by letter, word or line, a Select switch that turns every move into extending a selection, select word, line or everything, and cut, copy and paste. Turn it off in Settings
- The cursor now stays on screen as you move it or type at the bottom of a long note
- Tables copied from Obsidian, web pages and spreadsheets paste as real Markdown tables, including rendered tables (tab separated or HTML) and tables written without the outer pipes
- Tables in preview cope with escaped pipes, line breaks inside cells and rows without the outer pipes

## v0.5.18

- Settings are grouped under clear headings: text, typing and notes

## v0.5.17

- New settings: title new notes with today's date, and text new notes start with, such as a checklist

## v0.5.16

- New setting: how many lines of each note show on its card

## v0.5.15

- Text size and accent colour chosen in Control Room now apply here
- New Rotate with the phone switch in Settings

## v0.5.14

- New editor settings: text size, line spacing, ruled lines, typewriter font, autocorrect, capitalisation, word count bar and save delay

## v0.5.13

- Loading screens now say what they are doing instead of showing a bare spinner
- Wording tidied so no sentence needs a dash

## v0.5.12

- Long-press a note for Delete (it goes to the Trash, like swiping), next to Pin and Open
- Titles shrink to fit instead of being cut off
- Fixed a rare crash from Android's scrolling-screenshot feature

## v0.5.11

- Fixed: after switching to the tree or list view, Scribble could crash every time it opened. It now opens normally again on its own
- Notepad widget: checklist lines tick straight from the home screen (Android 12 and newer), headings stand out, and each widget can show the note you pick or your pinned/latest note

## v0.5.10

- Today's note (⋮ menu): one note per day, like Obsidian's daily notes. Opens today's or starts it with a plans checklist
- New from a template: Journal, Meeting, Checklist, Recipe, Project (with a task table) and Reading notes
- Fixed a crash when moving around with arrow keys or Tab while a screen was closing

## v0.5.9

- Lighter on the battery: in the Super look the drifting sparkles settle after a few seconds without a touch (the next tap wakes them), so a screen you're reading no longer redraws 25 times a second

## v0.5.8

- Changing the look in Control Room now restyles this app's widgets straight away, with no refresh or restart

## v0.5.7

- Widgets: choose the title colour in each widget's settings (automatic, white, dark, Aqua blue, Super pink or gold), on top of the background looks

## v0.5.6

- Markdown tables: written as | Column | Column |, they show as a real table in Preview. Tap a column heading to sort by it (numbers sort as numbers); tap again to reverse. The note is sorted too
- Insert table button in the editor toolbar starts a ready-made table
- Folder tree like Obsidian: the view button now cycles cards, list and tree. Folders named like “Work/Projects” nest inside each other and open with a tap
- Preview shows smaller headings and indented bullet lists

## v0.5.5

- List view: switch between cards and a list from the toolbar; in the list, swipe a note right to pin it or left to move it to the Trash
- Widgets can now match your look: new Bliss blue and Super neon backgrounds, and “Match my Wonderdesk look” follows whatever Control Room is set to

## v0.5.4

- Fixes from a full round of stress testing on every screen

## v0.5.3

- Remind me: set a reminder on any note (in an hour, this evening, tomorrow morning or any date and time); the notification opens the note
- Tap the tab you're already on to glide back to the top of its list
- Switches Android greys out as a “Restricted setting” are now explained: the guide walks you through unlocking them in three clear steps, and keeps showing them until the switch is really on (before, on Android 15 and newer it could hide the only hint)
- Nothing required stays hidden: if Android is blocking something the app needs, a slim banner says what and takes you straight to the switch, and Settings › Permissions and access lists everything with its state

## v0.5.2

- Notes glide into place when you add, pin or delete them
- Smoother everywhere: new screens, tabs and Settings ease in instead of snapping, and buttons, tabs, chips and switches give a light tap you can feel (following your phone's touch-feedback setting)
- Android's predictive back: the back gesture shows a live preview of where you're going
- Super look: tap sparkles now match Bliss Player: soft glowing sparks with trails, drifting rainbow colours and a faint aurora, instead of big solid circles

## v0.5.1

- Much faster and smoother: the app is now built optimised (the way Play Store apps are), so screens open quicker, scrolling is fluid and the APK is about a quarter of the size
- Screens and lists no longer decode pictures on the main thread, and skip redrawing parts that didn't change

## v0.5.0

- Polish and stability fixes from the suite-wide test pass
- Works properly in landscape: tabs move to a rail at the side so the content keeps its height, and screens that ran out of room now scroll
- If the app ever crashes it says so next time it opens and offers to send a report (only the error, app version and phone model, with file names and links removed). Turn on automatic sending in Control Room › Wonderdesk
- Lists can no longer crash when two items share a name

## v0.4.0

- Polish and stability fixes from the suite-wide test pass
- If the app ever crashes or freezes, a report goes to Control Room so it can be shared and fixed
- App icon art is larger and fills its tile without being cut off

## v0.3.1

- Edit documents anywhere on your phone: with all files access, the new “On this phone” view lists every text and Markdown file, and edits save straight into the original
- Opening a .txt or .md file from Wayfinder or another app edits that file in place instead of making a copy
- Notes folders on the phone are read directly instead of through Android's picker, which is much faster for big vaults
- App icon redrawn so nothing is cut off: round icons fill the space, the rest sit on an Aqua tile
- Smoother and lighter on the battery: the window background no longer animates, tap effects sleep until you tap, and nothing ticks while the app is in the background
- Opening the app no longer waits on Control Room to read the suite look

## v0.3.0

- Notes are real Markdown and text files in folders, usable as an Obsidian vault
- Markdown preview, [[links]] with backlinks, checklists
- Paint-style sketches and a notepad widget
