# Trial walkthrough: narration script (about 28 minutes)

Read this while you screen-record. Open the **Trial Setup Guide** page (source: `docs/runbooks/trial-setup-guide.html`) in one window
and keep a terminal beside it. Timestamps are targets, not rules. Lines in quotes are what to say. Lines starting with **Open**,
**Click** or **Run** are what to do on screen.

Rules for the recording:

- Never show a secret value. Show secret **names** only. Blur or skip any vendor page that displays a key. Pause the recording when you copy one.
- Use placeholders on screen (`<domain>`, `<project id>`). Do not show real addresses, account ids or tokens.
- Everything marked **check** in the guide is a vendor fact nobody has verified on a live account (menu names, prices, free-tier limits). Say so when you reach one.
- Nothing in these runbooks has run against a live OVH, Layerbase or Cloudflare account yet. Say that early.

---

## 00:00 Introduction (2 min)

**Open:** the Trial Setup Guide, top of the page. Scroll slowly past the title, the legend and the yellow strip.

"This is the setup for the 8-day trial. The trial runs the whole production stack once, in a throwaway OVH project, so that we find
every problem and every number before we spend real money. At the end of day 8 we decide go or no-go for production."

"The page is the checklist. Every step has a tick box, the exact command or page, how to tell it worked, and what to do if it did
not. My ticks stay in this browser only. The progress bar at the top shows how far I am."

"Three labels matter. **You** means only the owner can do it. **Agent** means the engineering agent can run it. And the yellow
**check** badge means a vendor fact that nobody has verified yet: menu names, prices, free-tier limits. If a screen looks
different from the guide, the screen wins, and we fix the guide."

**Click:** "Decisions this guide assumes" to open it. Read the list quickly.

"These decisions are final: OVH Managed Kubernetes free plan with one b3-16 node in the US region, Layerbase for Postgres,
Cloudflare Tunnel with no load balancer, web apps on Workers, R2 for images, Backblaze for backups, Resend for email, one Grafana
Cloud stack with an `env` label, Stripe on the same account, AI capped at two dollars a day, MCP private, no sign-up gate, no native
apps, 21 services, SOPS and age, and manual sync in Argo CD."

**Point at** the yellow strip, "Things that are different in production".

"Keep this strip in mind. The trial uses test keys, a disposable project, a throwaway domain and short-lived tokens. Production
uses live Stripe keys, a real project, fresh secrets and nothing from the trial. Each step also has an 'In production' line."

## 02:00 Phase 0: before you start (2 min)

**Click:** phase chip **0** in the top bar.

"Phase 0 is the owner and agent split. I create the accounts, add payment methods, turn on two-factor sign-in, copy each secret into
my password manager, approve every sync and make the final call. The agent runs tofu, kubectl, the secret rendering and the checks.
Secret values are never pasted into chat, an issue or a commit."

**Tick** the first step. **Point at** the tools step.

"The agent needs these tools on its machine. The first gate needs no cloud account at all."

**Run:** the gate command from the guide (`check_launch_set.py`). Show the output.

"It must say OK with 21 services and 5 platform Applications for prod. If it does not, we stop and fix the repository first."

**Point at** "Choose the trial names".

"One decision here matters. The trial domain should be a registered domain on my Cloudflare account, so that every host is one
label under it, like `api.<domain>`. Cloudflare's free certificate covers only one label. A name like `api.trial.afrotransact.com`
has no free certificate while it is proxied. Also tell the agent which mail domain your Resend DNS already covers, because the
sender address must be on it."

## 04:30 Phase 1: accounts and tokens (6 min)

**Click:** chip **1**. Go card by card. For each card, say what you create, then point at the "Secret names this produces" chips.

**OVH US.**
"OVH US is a separate entity from OVH Europe and Canada. Different console, different API address, different credentials. I create
the account, add a payment method, make a project called afrotransact-trial, and create API credentials at the US token page. I also
send OVH written questions: is the free Kubernetes plan available in US East, what is the real price of a b3-16, and what are the
storage and object lock terms. These are all unverified today."

**Layerbase.**
"For Layerbase I send ten written questions first, then buy the Dedicated 4 GB plan in Virginia. The free and Pro plans have too few
databases: we need 19. The two I care about most are whether the admin can create the PostGIS extension, and whether mutual TLS is
mandatory. Credit applications for OVH and Layerbase happen after launch, not now."

**Cloudflare, four cards.**
"Cloudflare is four jobs. One: the zone, with SSL mode Full strict and Always HTTPS. Two: R2, with two buckets, public media and
private evidence, a custom domain on the public one only, CORS on the private one, and two tokens, one per bucket, ten-day expiry.
Never one token for both, and never an account-wide admin token. Three: an API token for tofu and Workers. Four: the tunnel, which
tofu creates in phase 3, so we only note the token name now."

**Backblaze.**
"Backups go to Backblaze, never to R2, so that they survive a problem with the Cloudflare account. A private bucket, a lifecycle rule
that deletes hidden versions after one day, and an application key that can only touch that bucket. Then I test the key with the
rclone commands on the page, and I prove it is refused on any other bucket."

**Resend.**
"Resend: the DNS is done, so I only confirm the domain shows Verified, create a sending-only key for that domain, and add the
webhook. Notifications refuses to start in production mode without both the key and the webhook secret."

**Grafana Cloud.**
"One stack, afrotransact.grafana.net, US East. I copy the metrics and logs addresses and the two numeric user ids, create an access
policy for the trial with only metrics-write and logs-write, one more policy for loading rules from my laptop, and a service account
for dashboards. Every series and log line carries env=trial, so trial alerts never mail anyone."

**Stripe.**
"Stripe: same account, test mode. I copy the test keys and create three webhook endpoints with Snapshot payloads and the API version
on the page. Each endpoint has its own secret. The v1 live endpoints are not touched: v1 is deleted at the freeze, not now."

**OpenRouter.**
"OpenRouter: a key named for the trial, with a two-dollar credit limit set in their dashboard. The gateway caps spend at two dollars a
day in code, and this key limit is the backstop. I also turn off prompt logging and training and record the date."

**GitHub.**
"GitHub: a token that can only read packages, the prod environment with reviewers, and the first admin's real mailbox and a password
of at least sixteen characters."

"That is every account. Every secret name is on the page. We never read values aloud."

## 10:30 Phase 2: secrets (3 min)

**Click:** chip **2**.

"Secrets are rendered on my machine, encrypted with SOPS and age, and applied with a script. Argo CD never touches them."

**Run (do not show output with values):** the three `age-keygen` commands.

"One key for SOPS, a second operator key on another device, and a separate backup key. The backup private key stays offline. It
never goes into the cluster or the repository."

**Point at** "Fill the two local files".

"Two files, `config.env` and `secrets.local.env`, never committed. Use a new, empty state directory for the trial."

**Run:** the render command. Show only the list of file names.

"The render step generates every internal secret once and keeps it in the state directory, so a re-run changes nothing."

**Run:** the sops encrypt command and the `grep -L` check.

"Every file must contain a sops block. If grep lists a file, it is not encrypted, and I fix that before anything else."

**Point at** the escrow step. Slow down here.

"This is the step people skip. Two values can never be recovered: **IDENTITY_DATA_KEY**, which encrypts personal data, and the **JWT
signing key**. If I restore the database perfectly but lose IDENTITY_DATA_KEY, identity's encrypted fields are unreadable. The
escrow bundle holds them, plus the other generated keys and the database role passwords. It needs two recipients. I store it
offline, in two places, separately from the database backups, and I test the restore into a temporary folder now."

## 13:30 Phase 3: infrastructure (4 min)

**Click:** chip **3**.

"Nothing here has run against OVH yet. Expect first-run provider errors. The agent fixes them and re-runs."

**Walk the steps, running or showing the command for each:**

1. "Bootstrap the state bucket. One time per project."
2. "Apply the environment. Region US-EAST-VA-1, endpoint ovh-us, my IP in `admin_cidrs`, and for the trial only, disposable true. Disposable removes deletion protection and shortens KYC retention to one day. We never set it in the real project."
3. "Expected result: one free-plan cluster, one b3-16 node, a private network, some object storage buckets. No load balancer, no gateway, no database."
4. "Download the kubeconfig. If I cannot connect, my IP changed. Compare allocatable memory with the thirteen gigabytes we assumed. Test a one-gigabyte volume. Set the maintenance window to a quiet hour, because one node means every upgrade is downtime."
5. "Layerbase. First measure latency from a pod on the node. A few milliseconds is expected. Above ten, stop and ask which data centre it is. Then create the 19 databases and roles. The script also creates PostGIS in geo and fulfilment, and btree_gist in bookings, as the admin. Fulfilment's migration would fail on a managed server without it. I verify with `select postgis_version()`."
6. "Cloudflare Tunnel. Only api and auth are exposed. There is no mcp host and no grafana host, and there must never be. One caveat: the Cloudflare module only accepts dev, uat or prod today, so the trial needs its own settings file. The page flags this."
7. "Apply the encrypted secrets. The script refuses any secret whose mandatory keys are empty."
8. "Pin the images. Every launch service needs a full commit sha in its prod values, from a green main build. The gate must say OK."
9. "Bootstrap Argo CD, trimmed, with auto-sync off everywhere. The check: 26 Applications, all OutOfSync, none syncing by themselves."

## 17:30 Phases 4 and 5: first sync and web apps (3 min)

**Click:** chip **4**.

"Now I sync by hand, and I wait for Healthy each time. Order matters."

"Platform first: namespaces, Envoy, the collector, the edge, then the lean bundle with Kafka, Temporal, Valkey, Meilisearch, the backup
job and Alloy. Then identity alone, because it issues the tokens everything else needs. Then the Java services three at a time, because
JVM start-up spikes the CPU. Then the Go services, with the gateway last, because it will not start without its session key and the
Stripe publishable key."

"After each wave I run the memory check on the page and write the numbers down. They are the decision data."

"A failed database migration leaves the pod crash-looping. We fix forward. We never edit an applied migration."

**Click:** chip **5**.

"The three web apps are Cloudflare Workers. The normal pipelines are wired to the production hostnames, so the trial uses the
workflow that takes its hosts from the domain I give it. The backend and the Workers must agree on the hosts, or sign-in loops with a
redirect mismatch. The page flags that the trial override of the domain is not written down anywhere yet."

"There are no native apps. On a phone I open the storefront, tap Share, then Add to Home Screen. That is the installable web app."

## 20:30 Phase 6: smoke test (4 min)

**Click:** chip **6**. Do each item live if time allows, otherwise show the screen and the expected result.

1. "**Edge.** Auth discovery and the BFF health both return 200. An unsigned POST to each Stripe webhook path returns 400. A 404 means the route is missing."
2. "**Sign-up.** I create an account with my own mailbox and enter the six-digit code. In Resend the message shows Delivered, and the headers show DKIM, SPF and DMARC pass. If no mail arrives I check Kafka and the notifications logs first."
3. "**Staff.** I sign in to the admin console. Staff multi-factor is mandatory, so the first sign-in forces setup. I change the password."
4. "**Product with a tax decision.** A seller lists a product. Every variant's tax classification waits for a reviewer, so I approve it in the Tax review screen, then in trust-ops. The product must appear in search. A listing that bounces back to review means the tax gate closed it, and that is the gate working."
5. "**Checkout.** Test card 4242. The order is authorised, nothing captured."
6. "**Admin status.** I set the shipment to picked up and write a note. That captures the customer's money. The history shows who and when. Today the screen does not warn that it charges the customer, so a second person must review the day's captures."
7. "**Image upload.** The browser uploads into the private bucket's quarantine folder, media cleans it and copies it into the public bucket. The URL is on the media host and returns 200. The quarantine path returns 404."
8. "**MCP.** No DNS name. The service is cluster-internal. From inside the cluster, a call with no token returns 401. A shopper token cannot call staff tools."
9. "**AI.** One Lola message. The day's total in the ai database is far below two dollars."
10. "**Alerts and dashboards.** Alloy is running, and the up query for env=trial shows one row per service. I load the rules and dashboards from my laptop. I create the on-call contact point and press Test. Trial alerts go to an empty contact point on purpose."

## 24:30 Phases 7 and 8: backup, restore and Stripe (3 min)

**Click:** chip **7**.

"A backup that has never been restored is not a backup. Targets are to lose at most 24 hours and be back in 4. Those are targets. The
first drill gives the real numbers."

**Run:** the manual backup job. Show the log lines `backed up` and `backup complete: 19 databases`.

"Then I restore eight databases into a scratch server, never over the trial. I note the start and end time. Then the part that
matters: I boot identity against the restored copy with the escrowed secrets and sign in. That proves the key escrow works. I write
one row in the drill log."

"One gap I will say out loud: the images in the two R2 buckets are not covered by the database job. The nightly copy to OVH object
storage is still to do."

**Click:** chip **8**.

"Phase 8 is the Stripe question that blocks the cutover. Version one's connected accounts have a Full dashboard. Version two creates
accounts with no dashboard and uses separate charges and transfers. We do not know whether Stripe allows that on the old accounts. In
test mode I create an account shaped like version one's, link it, and run each flow: charge, transfer, refund with reversal, payout
schedule, capability request, embedded onboarding, the events, and a dispute. I write down what is allowed or refused. Then I choose
keep, re-onboard or hybrid, and send the twelve questions to Stripe support. This is a launch blocker."

## 27:00 Phases 9 and 10, wrap-up (1.5 min)

**Click:** chip **9**. Point at the table.

"Eight days. Day 1 accounts and secrets. Day 2 infrastructure. Day 3 first sync and web apps. Day 4 the smoke test. Day 5 backup,
restore and the Stripe check. Days 6 and 7 are measurements: memory per service, load at fifty shoppers, node reboot downtime, rolling
update downtime, Grafana volume against the free limits, and cost per day times thirty. Day 8 is the decision."

**Click:** chip **10**.

"The go or no-go list. I tick a line only if I saw it. The blockers are the restore drill, the connected-account decision, and
Layerbase's written answers on backups and point-in-time recovery. If any blocker is open, the answer is no-go until it is closed or
I accept it in writing."

"Then clean up: revoke trial tokens, delete the trial Grafana policy, empty and delete the trial buckets, and never reuse a trial
secret in production."

"That is the whole trial. Every command is on the page with a copy button. Thank you."

---

## Before you press record (checklist)

- [ ] The guide is open and the tick state is cleared (Clear my ticks, press twice).
- [ ] The terminal is in the repository root with placeholders in the history only, no real values exported.
- [ ] Vendor pages that show keys are not open in a visible tab.
- [ ] Notifications and password-manager popups are off.
- [ ] You know which mail domain your Resend DNS covers.

## Things to say honestly if asked

- None of this has run against a live OVH, Layerbase or Cloudflare account. Everything marked check is unverified.
- Prices and free-tier limits are not in the repository. The trial is how we get them.
- The connected-account question for Stripe is open and may change the cutover plan.
