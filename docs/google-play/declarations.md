# Play Console declaration drafts

These answers describe the 1.7.0 code. Jack must match the final Console forms to
the exact signed build and the publisher's actual practices before submitting.
Do not answer "no data collected" for an app that includes optional cloud audio
uploads. Data safety applies across the app's supported configurations.

## App access and content

- App access: no Speakeasy login. The bundled English speech model is available
  immediately. Overlay, microphone, and Accessibility setup are explained in app.
  An external API key is needed only for the optional provider chosen by the user.
- Ads: none.
- Category: Productivity.
- Audience: general productivity users. The listing is not directed at children
  under 13. Confirm target age groups during account setup.
- Content rating: complete the IARC questionnaire for the shipped app. There is
  no hosted social feed, chat service, gambling, or app-supplied mature content.
- Payments: no in-app purchases or subscription in this build. Third-party cloud
  provider charges are outside the app.
- Account deletion: the app does not create Speakeasy accounts. Local data can be
  cleared in Android settings. Provider accounts are managed with that provider.

## AccessibilityService

Accessibility tool: **No**. This app is a general dictation tool. Do not set
`isAccessibilityTool=true` to avoid the disclosure requirement.

Purpose: **App functionality**.

Proposed explanation:

> Speakeasy's user-enabled floating microphone works alongside the user's current
> keyboard. Accessibility observes keyboard visibility and the active editable
> field, including its text, package, and selection. This shows the floating
> control, selects writing style, preserves surrounding text, and inserts speech
> the user explicitly dictates. Password fields are excluded when identified by
> Android. Editor context stays on device and is not sent to cloud transcription.
> Speakeasy does not send messages, click other controls, plan tasks, or operate
> apps autonomously. A separate in-app disclosure requires affirmative consent
> before these capabilities run, including when the service was enabled directly
> in Android settings. Users can withdraw consent in the app or disable the
> service in Android settings. The optional keyboard works without Accessibility.

Off-device collection through Accessibility: **No** for the code's editor context.
Audio collected separately through the microphone is covered by Data safety.

Video still required. Show both decline and acceptance of the in-app disclosure,
the Android service setup, a real editable field, recording, Stop, insertion,
password exclusion, and withdrawal. Do not submit a design mock-up or stale video.

## Foreground service types

`microphone`:

> Captures speech only after the user taps Start dictation. A visible recording
> control and notification offer Stop. Capture continues across keyboard and screen
> changes until explicitly stopped or interrupted by Android. Stop & keep saves audio
> without transcription. The microphone is released before transcription. Startup,
> recovery and idle mode never activate the microphone automatically.

`specialUse`:

> Keeps a user-enabled floating dictation control available when an eligible text
> field and keyboard are visible. The control works with the user's existing
> keyboard. The same service owns capture for the optional keyboard even without
> overlay permission. The notification exposes Stop during capture and Pause new
> recordings while idle; interrupted audio exposes Recover. Pause persists across
> restarts and does not stop an existing capture. The service remains idle until
> the user explicitly starts recording.

Supply Google's requested video/evidence and check the relevant Console use-case
choices. Google must approve the special-use case; the manifest is not approval.

## Data safety worksheet

| Data | Leaves the phone? | Purpose and choice | Retention |
| --- | --- | --- | --- |
| Voice recordings | Only in optional Cloud mode, after consent | App functionality; optional | Selected provider's terms; do not claim ephemeral processing without verifying them |
| Configured prompt and model | Sent with applicable cloud requests | App functionality; optional | Provider-dependent |
| API key | Sent to the selected cloud provider for authentication | App functionality; optional | Stored privately on device until changed/cleared; provider processing is external |
| Transcript and editor context | On-device processing and recovery; no automatic upload | App functionality | Recovery expires after 24 hours on store access or earlier insertion/discard |
| Diagnostic log | Local until the user explicitly shares it | Troubleshooting; optional | Up to 5,000 lines, or until cleared/uninstalled |
| IP address during cleanup model download | Normal request to Hugging Face/CDN | Optional model download | External service's terms |

Proposed collection entry: **Audio files / Voice or sound recordings**, optional,
for app functionality. Configured free-text prompts may require **Other
user-generated content**. Review any additional data types implied by the
publisher's chosen providers and support practices.

Cloud uploads go to a third party chosen by the user. The sharing answer depends
on whether the applicable service-provider or user-initiated-transfer exception
in Google's Data safety guidance applies. Until that is established for the final
provider configuration, use a conservative sharing declaration. Do not claim no
sharing based solely on Speakeasy not operating its own server.

Encryption in transit: HTTPS is required for cloud providers in release builds.
The optional model download also uses HTTPS. Development builds permit HTTP for
local tests and are not the Play release.

Deletion: users can discard recoveries, clear logs, and clear app storage. A cloud
provider may retain recordings it already received; direct deletion requests to
that provider. Do not promise that uninstalling deletes remote provider data.

Privacy policy:
https://raw.githubusercontent.com/jschof1/wysawyg-android/master/app/src/main/assets/privacy-policy.txt

Publisher: Aspect Studio Software.
Support: info@aspectstudio.net.
