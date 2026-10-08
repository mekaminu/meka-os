# ADR-009: Provider interfaces for external services

**Status:** Accepted, 2026-10-01. This follows the owner's amendment: nothing hard-coded to Gmail or any one provider.

## Decision
Each external capability is an interface in `core/providers` (shared types) with adapters in `backend/` (server-side OAuth) or in the platform apps (on-device APIs).

```
interface EmailProvider    { listChanges(cursor), getMessage(id), send(draft, idemKey), modifyLabels(id, add, remove), archive(id) }
interface CalendarProvider { listCalendars(), listEvents(range, cursor), upsertEvent(event, idemKey), deleteEvent(id) }
interface ContactsProvider { listChanges(cursor), get(id) }
interface FileProvider     { list(folder, cursor), fetch(ref), put(blob, idemKey) }
interface SportsProvider   { teamFixtures(teamRef, range), fixture(id) }       // poll + diff; changes emit FixtureChanged
interface NewsProvider     { fetch(topic, since) }                              // clustering is ours, not the provider's
interface MessagingChannel { capabilities(); ingest(event); reply(threadRef, text, idemKey) }
```

- Every mutating call takes an idempotency key.
- Every adapter declares `ProviderCapabilities`: read, write, push-vs-poll, rate limits and quirks. The UI never offers what the adapter can't do.

**First adapters, in the order needed:**
| Capability | First adapter | Why / notes |
|---|---|---|
| Sports | football-data.org | Free tier covers La Liga and the Champions League at 10 calls/min, but scores are delayed. Fixtures are what we need, so we poll daily plus hourly on match day. API-Football is the second adapter if coverage gaps appear. |
| Calendar | Google Calendar and Microsoft Graph (`GoogleCalendar`, `MicrosoftCalendar`) | Read-only by default. **Calendar editing (2026-10-08):** per account, only after the owner taps Allow editing, the sign-in also asks for `CalendarProvider.writeScope` (Google `calendar.events`, Microsoft `Calendars.ReadWrite`); the server records `can_edit` only when that scope was actually granted, and Stop editing clears it at once. Writes (later slices) are allowed only on accounts with `can_edit`. |
| Email | Owner's primary provider; to be confirmed | **Gmail:** an OAuth app in "Testing" status has refresh tokens that expire after 7 days (verified). A personal-use Production-unverified app avoids this, but shows an unverified-app warning and has a 100-user cap. Restricted-scope verification isn't needed for personal use. **Microsoft Graph:** supports personal accounts. |
| Messaging | Android notification listener (WhatsApp, SMS) | See the messaging constraints below |
| Voice (call assistant) | Twilio Programmable Voice (`TwilioVoice`, 2026-10-08; approved by Meka 2026-10-07) | `VoiceProvider { verify(url, form, header); parse(step, form); render(reply, baseUrl); deleteRecording(ref) }` in backend/CallAssistant.kt. Webhooks are form posts checked against the account's signature (`X-Twilio-Signature`, HMAC-SHA1 with the auth token in Secrets Manager `meka-os-dev/voice/twilio`); replies are TwiML. The flow itself (`CallAssistant`, fixed script in the core) is provider-neutral. A recording is deleted from Twilio once its transcript arrives. |
| News | BBC News public RSS feeds (`BbcNewsRss`, 2026-10-06) | No key or sign-in; one plain GET per topic, at most hourly, from the server. `NewsProvider { topics; headlines(topic) }`: the server mirrors the newest items of every topic and the apps filter by the topics chosen, so no choice of Meka's is ever sent anywhere. |
| News (AI, tech, Barça) | `PublicNewsFeeds` (2026-10-08): Mundo Deportivo and Sport (Barça RSS), Google News RSS search (Barça; Spain football), The Verge AI (Atom), TechCrunch AI, MIT Technology Review AI, OpenAI news (RSS), Hacker News front page via hnrss.org (200+ points) | Same rules as the BBC: no key, plain GET at most hourly, untrusted, https links only, tracking parameters dropped. Several feeds per topic merged newest first, the same story once (normalised title); a feed that fails or is empty is skipped, the topic fails only when none could be read. Google News is a fallback that may change (the Spanish papers still carry Barça). Anthropic publishes no news feed, so it is not included. |
| News pictures | The feeds' own picture links (`media:thumbnail`, image `media:content`, image `enclosure`, else the first `<img>` in the description), fetched by `NewsImages` (2026-10-08) | Same rules as the feeds: plain GET of public content, nothing about Meka sent; https only, public hosts only (no loopback, private, link-local or unique-local addresses, checked on every redirect), image types only, at most 5 MB, at most 40 new pictures and 90 s per hourly refresh. Decoded with the JDK's ImageIO (size checked before decoding; WebP isn't asked for, as the JDK can't read it) and re-encoded as a JPEG ≤ 320 × 320 px and ≤ 30 KB, so nothing of the original file reaches a device. Kept in the server's own database (no S3 bucket, no paid image service; a few MB). The og:image of an article page is not fetched (that would mean reading every article page). |

**Messaging constraints (verified 2026-10-01):**
- **No unofficial WhatsApp clients.**
- **Sideload restriction:** on a sideloaded APK, notification access sits behind Android's "restricted settings" gate (Android 13+). The user must allow restricted settings in App info before granting access. Onboarding will guide this honestly.
- **OTP redaction:** Android 15+ redacts notification content for untrusted listeners when an OTP is detected. We accept that; it is desirable.
- **Replies:** replying via a notification's `RemoteInput` action is used only while the notification exists. Otherwise we fall back to copy-and-open. Reply sending is capped at autonomy Level 3 (ADR-006).
