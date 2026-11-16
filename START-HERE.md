# Chandrika Dhaba - start here

## Run it in the terminal

You need Java 17 or newer (`java -version`).

    cd chandrika-dhaba
    java -jar dist/chandrika-dhaba.jar          # ready-made jar, no Maven needed

or build it yourself (needs Maven + internet the first time):

    mvn clean package
    java -jar target/chandrika-dhaba.jar

or `./run.sh` (Linux/Mac) / `run.bat` (Windows).

Then open  http://localhost:8080/manager
First login: username `manager`, password `chandrika123`.
Go to **Settings > Manager login** and set your own username and password straight away.

Stop with Ctrl+C. Use a different port with `--port 9090`.

## Make the data permanent (also on free Render)

Everything you save (dishes, pictures, waiters, bills, settings, your login) is copied to your Google Drive,
folder **Chandrika Dhaba - Data / Backup**, a few seconds after every change. When the app starts on an empty
disk it copies it back by itself, before the first page opens. Nothing to click.

One-time setup:

1. Run the app, log in, open **Settings > Google account & Drive**.
2. Paste your Google Client ID and Client Secret, press **Sign in with Google**, choose your Gmail
   (see GOOGLE-DRIVE-SETUP.md for creating the Client ID; on Render the redirect URI is
   `https://YOUR-APP.onrender.com/api/manager/google/callback`).
3. Press **Show key for Render** and copy the long value after `DHABA_GOOGLE_REFRESH_TOKEN=`.
4. Open `src/main/resources/google-defaults.properties` and fill in the three lines:

       client.id=...
       client.secret=...
       refresh.token=...

5. Commit and push to your **PRIVATE** GitHub repository (the file holds secrets - never make the repo public).
   Render redeploys.
6. In Settings > Manager login choose your own username and password.

From then on, every restart (Render sleep, redeploy, new folder) reconnects to Drive and restores your data,
and you log in with your own username and password.

Notes
- The Settings page shows "Last backup" - make a change, wait for a new time, and the change is safe.
- If Drive already holds data the app has not loaded, backups pause and Settings tells you why. The app never
  overwrites a newer Drive backup with empty/sample data.
- Free Render sleeps after ~15 minutes idle; the first visit afterwards takes about a minute while it restores.
- Order numbers restart at 1 every day after midnight (India time). Bill numbers keep counting up.
- A password set with `DHABA_PASSWORD` / `--password` only applies until you choose your own in Settings.
