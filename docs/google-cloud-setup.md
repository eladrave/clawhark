# Rave AI cloud setup

Verified on 2026-11-01:

- Google Cloud project: `eladrave-rave-ai`
- Display name: **Rave AI**
- Project number: `89543816751`
- Google Drive API: enabled
- Administrative gcloud login: verified for the project owner

OAuth branding has been prepared with app name **Rave AI**, external audience, and the owner's support/contact email. Creation is pending the owner's approval of Google's required [API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy). No app OAuth client or watch upload authorization has been created yet.

Next configuration: create a **TVs and Limited Input devices** OAuth client, request only `https://www.googleapis.com/auth/drive.file`, and allow the owner's account during testing. Keep client configuration and tokens out of Git. The watch's Drive consent is separate from administrative gcloud authentication.

The app creates a `Rave AI` folder in Drive after successful watch authorization. A real recording/upload roundtrip and physical Pixel Watch 5 qualification are pending. See [recorder-qualification.md](recorder-qualification.md).

The repository remains a fork of `ivar2000/clawhark` at `eladrave/clawhark` so the upstream relationship and existing review links remain available.
