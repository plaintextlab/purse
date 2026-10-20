# purse

A simple expense tracker. The Android app is in [`android/`](android). A desktop
app (Rust, Windows/macOS/Linux) is planned and will share the
[sync format](docs/sync-format.md).

## Features

- **Fast entry**: amount → category → save. Date and time default to now, and
  the account you used last is preselected. Details are optional. There is also
  a launcher shortcut and a widget button for adding.
- **Expense, income and transfers** between accounts (for example paying a
  credit card bill from savings).
- **Accounts**: savings, current, credit card, cash, wallet/UPI, FD, RD, mutual
  fund, stocks, loan. You can **set the current balance** at any time; the app
  records an adjustment so your history stays intact. You can **exclude** any
  account from the total (for example mutual funds or cash) or archive it.
- **Categories**: add, rename, delete, and pick an emoji for each. Sensible
  defaults are created on first launch.
- **Dashboard**: balance, spent and earned this month, an optional monthly
  budget, daily spending bars, top categories and recent transactions.
- **Calendar**: a month grid showing how much you spent each day.
- **Activity**: transactions grouped by day, with search and type filters.
- **SMS tracking** (opt-in): templates built from your own sample messages; see below.
- **Git sync** into a folder of your shared sync repository; see below.
- **Home-screen widget**: tap its title to cycle through *Overview* (balance,
  spent, earned, budget left), *Accounts* and *Today*.
- **Themes**: Gruvbox (default), Nord, Everforest, Catppuccin, Tokyo Night,
  Rosé Pine, Solarized and Dracula, each with light and dark variants, plus
  Material You on Android 12+.
- **Backup**: export or import a single JSON file.

## SMS templates

1. Settings → SMS tracking → **+**.
2. Fill in the **Sender** pattern with wildcards. For example, `*HDFC*` matches
   `AD-HDFCBK-T` and `VM-HDFCBK`. Separate several patterns with commas.
3. Paste a **sample SMS**, then type the amount, account digits and merchant
   exactly as they appear in it. **Guess** fills these in for you, and
   **Build pattern** generates the template. For your example:

   ```
   Sent Rs.{amount}
   From HDFC Bank A/C *{account}
   To {merchant}
   ```

   Dates, times, reference numbers and phone numbers in the sample are turned
   into `{*}` (match anything) automatically. Text after the last value is
   dropped because banks change their footers often. You can edit the pattern
   by hand, and the preview shows what it extracts from the sample.
4. Choose whether the template creates an expense or income, a default
   category, and an account. *Match by digits* routes the message to the
   account whose **Account / card digits** match `{account}`; set those digits
   on the account screen (for example `6254`).

Matching messages become transactions as they arrive. If you leave
notifications on, tap the notification to categorise the transaction. Use
**Import from inbox** to pick up older messages. Duplicates are skipped.

> Google Play only allows SMS permissions for default SMS apps, so this app is
> meant to be sideloaded or distributed through F-Droid or GitHub releases.

## Git sync

Settings → Git sync. Enter an **https** repository URL, a branch, a username
and a personal access token (password logins are not supported by GitHub or
GitLab). Use a fine-grained token limited to the sync repository with
*Contents: read & write*.

Data goes into `<folder>/` inside that repository. The folder defaults to the
app name and is fixed once saved, so renaming the app does not move your data.
Other folders in the repo are never touched. With *Sync automatically* on, the
app syncs about 30 s after a change and every 6 hours. Edits from several
devices merge per record, and the newer edit wins. See
[docs/sync-format.md](docs/sync-format.md).

The token is stored in the app's private storage, and Android backup is
disabled for the app so the token is never uploaded.

## App name in the launcher

Android cannot rename an installed app's launcher label at runtime. There are
two options:

- **Build time**: set `purse.appName` in `android/gradle.properties` (or pass
  `-Ppurse.appName="My Money"`) and rebuild.
- **Runtime**: Settings → *App name* changes the name used in the app and the
  widget. *Add "…" to home screen* then pins a shortcut with that name.

## Building

Requires JDK 17 and the Android SDK (API 35).

```sh
cd android
./gradlew :core:test assembleDebug        # app/build/outputs/apk/debug/
./gradlew assembleRelease                 # signed with PURSE_KEYSTORE* env vars, else debug key
```

GitHub Actions (`.github/workflows/android.yml`) runs the tests and uploads both
APKs as an artifact. If you add the repository secrets `PURSE_KEYSTORE_BASE64`,
`PURSE_KEYSTORE_PASSWORD`, `PURSE_KEY_ALIAS` and `PURSE_KEY_PASSWORD`, every
build is signed with the same key, so updates install over each other.

### Layout

```
android/core   pure Kotlin: money, wildcard + SMS templates, sync format/merge (unit tested)
android/app    Compose UI, Room database, SMS receiver, JGit sync, Glance widget
docs/          sync format shared with future clients
```
