# FOOD KCN 1.8.1 - Google Play release checklist

## Code changes in this package
- Target/compile SDK 36.
- versionCode 14 / versionName 1.8.1.
- Added in-app account/data deletion request flow (`delete_account`).
- Added in-app privacy/data explanation screen.
- Removed plaintext password persistence; only the phone number is remembered.
- Disabled Android backup for account data.
- HTTPS-only network configuration remains enabled.

## IMPORTANT backend requirement before production upload
The Android package alone cannot create a real account-deletion endpoint. The server at `https://com11h.com/api/index.php` MUST implement authenticated POST action `delete_account` and return JSON `{"ok":true}` only after the account/data deletion request has actually been recorded/processed.

The public web resource used for Play Console data deletion is `https://com11h.com/delete-account.php`. It is publicly accessible and lets a user submit a deletion request without requiring the app.

You also need a dedicated public Privacy Policy URL in Play Console. The supplied Android ZIP did not contain the website/backend, so I could not verify a dedicated privacy-policy URL. Do not invent one in Play Console; use the actual live policy URL on com11h.com.

## Build
1. Open `android/` in Android Studio or use the included GitHub Actions workflow.
2. Use JDK 17.
3. Sync Gradle (AGP 8.7.3 / Gradle 8.9).
4. Create `android/keystore.properties` from `keystore.properties.example` and point it to your existing release keystore.
5. Build `app:bundleRelease`.
6. Verify the generated AAB is signed and upload it to Play Console.

## Play Console declarations
- Complete Data safety accurately.
- Complete Data deletion questions.
- Add the live Privacy Policy URL.
- Add the live Account/Data deletion URL (`https://com11h.com/delete-account.php`) after verifying it works without the app.
- Upload screenshots and store listing.
