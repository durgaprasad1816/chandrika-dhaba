# One-time Google setup (about 5 minutes)

1. Go to https://console.cloud.google.com and create a project (any name).
2. **APIs & Services -> Library** -> search **Google Drive API** -> Enable.
3. **OAuth consent screen**: User type *External*, fill the app name and your e-mail. Under **Test users** add the Gmail
   you will sign in with. (Leave the app in "Testing"; no Google review is needed for your own account.)
4. **Credentials -> Create credentials -> OAuth client ID -> Web application**.
   Under **Authorised redirect URIs** add exactly the address shown in the app at
   *Manager -> Settings -> Google account & Drive -> Google app keys* - normally `http://localhost:8080/api/manager/google/callback`.
5. Copy the **Client ID** and **Client secret** into that same Settings box and press **Save keys**.
6. Press **Sign in with Google**, choose your Gmail and allow Drive access.
7. Paste your Drive folder link (open the folder in Drive, copy the address bar) and press **Save folder**.

Notes
* Sign in from `http://localhost:8080/manager` on the computer running the app (Google does not allow http:// on other
  addresses). On a public https:// website, use that address instead and add its callback address in step 4.
* Access given to the app is "Google Drive" (needed so it can write into a folder you already own). Disconnect any time in Settings
  or at https://myaccount.google.com/permissions.
* Keys and the sign-in token are stored in `data/google.json` - keep the `data` folder private and do not share it.
* Alternative: set environment variables `DHABA_GOOGLE_CLIENT_ID` and `DHABA_GOOGLE_CLIENT_SECRET` instead of typing the keys.
<<<<<<< HEAD

---

## Running on Render (free plan) without losing data

Render's free plan erases the app's disk every time it sleeps or restarts, which is why edits used to disappear.
The app now backs up `store.json` (dishes, waiters, settings), bills and collections to your Google Drive
(**Chandrika Dhaba - Data / Backup**) a few seconds after every change, and restores them on start-up when the disk is empty.

One-time setup:

1. In Settings > Google account & Drive, sign in with Google (the redirect URI must be `https://<your-app>.onrender.com/api/manager/google/callback`).
2. Press **Show key for Render** and copy the value.
3. In Render > your service > Environment add:
   - `DHABA_GOOGLE_CLIENT_ID`  (from Google Cloud)
   - `DHABA_GOOGLE_CLIENT_SECRET`  (from Google Cloud)
   - `DHABA_GOOGLE_REFRESH_TOKEN`  (the value you copied)
   - `DHABA_PASSWORD`  (your manager password, so it does not reset either)
4. Save; Render restarts the app. From now on every restart restores your latest data from Drive.

Pictures already go straight to Drive. Use **Back up now** to force a backup. Free Render also sleeps after ~15 min idle;
the first visit afterwards takes about a minute while the app wakes and restores.
=======
>>>>>>> origin/main
