# ArogyaDoot

### Offline-first AI field assistant for ASHA workers

**ArogyaDoot 1.0.0** is an Android application designed to help Accredited Social Health Activists (ASHAs) find source-backed guidance, organise household visits, track follow-ups, maintain registers, and prepare field records—even when internet access is limited.

**Ask the guide. Check the citation. Record the visit. Follow up.**

> **Status:** Version 1.0.0 working prototype. ArogyaDoot is a decision-support and documentation tool, not a diagnostic device or replacement for an ANM, doctor, or official healthcare protocol.

## See ArogyaDoot in action

<table>
<tr>
<td width="50%" align="center"><img src="docs/readme-v1/265409.jpg" alt="ASHA dashboard showing visits due and overdue follow-ups" width="300"><br><b>Prioritised field work</b></td>
<td width="50%" align="center"><img src="docs/readme-v1/265407.jpg" alt="ArogyaDoot dashboard with visits, people, Ask SOP and registers" width="300"><br><b>One field dashboard</b></td>
</tr>
<tr>
<td align="center"><img src="docs/readme-v1/265405.jpg" alt="Ask SOP answer from on-device Gemma 3n E2B" width="300"><br><b>Offline Ask SOP</b></td>
<td align="center"><img src="docs/readme-v1/265403.jpg" alt="Highlighted excerpt with guide title, page citation and Open PDF" width="300"><br><b>Page-level source verification</b></td>
</tr>
<tr>
<td align="center"><img src="docs/readme-v1/265401.jpg" alt="Hindi guidance shown in the ASHA assistant" width="300"><br><b>Field-friendly language</b></td>
<td align="center"><img src="docs/readme-v1/265399.jpg" alt="Offline official guide library showing document and passage counts" width="300"><br><b>Local document library</b></td>
</tr>
<tr>
<td align="center"><img src="docs/readme-v1/265397.jpg" alt="Registers, monthly reports, drug kit and activity diary" width="300"><br><b>Registers and monthly reporting</b></td>
<td align="center"><img src="docs/readme-v1/265395.jpg" alt="Offline MCP antenatal card preview and PDF sharing controls" width="300"><br><b>Shareable offline documents</b></td>
</tr>
</table>

*Screenshots show a demonstration dataset and representative workflows. Counts visible on screen are sample records or the installed knowledge-base inventory, not measured population outcomes.*

## Why ArogyaDoot?

ASHA workers combine household outreach, maternal and child health activities, immunisation follow-ups, referrals, stock records and monthly reporting. The required guidance is distributed across many documents. Finding the relevant passage during a visit can be difficult, especially in low-connectivity settings.

ArogyaDoot connects **guideline retrieval** with **practical field-work management**. Its purpose is to reduce searching and transcription friction while keeping the source and the healthcare worker's judgement visible.

## Features

| Area | What the app provides |
|---|---|
| **Ask SOP** | Ask a health-workflow question and review a response grounded in the installed guide collection. |
| **Citations** | Show document title, page references, supporting text and an option to open the source PDF where available. |
| **Offline AI** | Run local text assistance using the Gemma 3n E2B LiteRT-LM model downloaded during setup. |
| **Official guides** | Browse/search locally installed reference documents by title, organisation and language. |
| **Visits and people** | Record field visits and maintain household/person information. |
| **Follow-ups** | Review work due and overdue and plan subsequent visits. |
| **Registers** | Access activity diary, drug-kit records, monthly lists, claims, and structured reporting workflows. |
| **Document output** | Preview, share or print supported forms and summaries as PDFs. |
| **Speech and OCR** | Speech-input and image/document extraction workflows are available; performance varies by device and input quality. |
| **Optional online AI** | Configure a Groq API key for cloud-based Qwen assistance when connectivity and user choice permit. |

### How Ask SOP works

1. An ASHA asks a question in everyday language.
2. ArogyaDoot finds relevant passages in the installed guide collection.
3. The selected AI mode helps formulate a readable response using retrieved context.
4. The response exposes source information so the worker can inspect the original wording and page.
5. The ASHA decides what action to take under the applicable SOP and clinical-supervision process.

The local library shown in the current demonstration contains **68 documents, 2,884 pages and 5,389 indexed passages**. This describes that installed dataset only; other installations may differ.

## Installation

### Install an APK

1. Obtain a tested ArogyaDoot **1.0.0** APK from the project's [Releases](../../releases) page when one is published, or from the project maintainer.
2. Install it on a compatible Android device. Android may require permission to install an app from the selected source.
3. Open **ArogyaDoot** and complete the on-screen setup steps.
4. Download the supported local Gemma model when prompted. Leave enough free storage for both the model and its temporary download file.
5. Install or select the knowledge-base/official-guide package as offered by the app and confirm its available-document count.
6. Choose **Offline** to use local assistance. For optional cloud assistance, enter your own Groq API key in the app's settings.

**Local model:** `gemma-3n-E2B-it-int4.litertlm`. The model is downloaded separately; it should not be committed to this repository. [Download source](https://huggingface.co/datasets/EktaSikka/Gemma/resolve/main/gemma-3n-E2B-it-int4.litertlm?download=true).

**Cloud key:** A Groq key is optional. Never include a real key in GitHub issues, commits, screenshots, or build logs. Cloud requests require connectivity and may transmit the text submitted to the external provider; do not send identifiable patient information without an appropriate authorised workflow.

### Build from source (Windows)

Prerequisites: Git, Android Studio/Android SDK, a compatible JDK, and the project-specific Android/Gradle dependencies. Some larger runtime assets are distributed separately and may be absent from a fresh Git checkout.

```powershell
git clone https://github.com/ektasikka1989-cmyk/Arogyadoot.git
cd Arogyadoot
.\gradlew.bat :app:assembleDebug
```

Expected debug output: `app\build\outputs\apk\debug\app-debug.apk`.

If a fresh checkout fails because a large offline runtime asset is missing, consult the project-specific setup documents and acquire that asset from its authorised source. Do not assume that a successful Gradle build alone proves every offline AI, OCR or speech path works on a device.

## Technology

- **Android / Kotlin** application and field-workflow components.
- **Gemma 3n E2B** local model through **LiteRT-LM** for offline assistance.
- **On-device document retrieval** for source-backed SOP answers.
- **Optional Groq-hosted Qwen** integration for cloud requests.
- **OCR, speech and PDF components** supporting capture, dictation and forms.

The exact runtime and provider availability depends on the installed build, model, device capabilities and configuration.

## Privacy, safety and limitations

- The app assists with reference lookup and record keeping; it does **not** diagnose, prescribe or replace a healthcare professional.
- Verify safety-critical instructions against the cited document and current local protocol. Escalate uncertainty or warning signs to the ANM/appropriate clinician.
- Use demonstration or properly authorised records when testing. Do not publish real patient-identifiable data.
- Offline features reduce dependence on mobile internet, but require the appropriate model and guides to be installed on the device.
- Local inference is resource-intensive; memory, speed, ABI and Android page-size compatibility can vary. **16 KB native-library compatibility has not been certified across all packaged components.**
- OCR on tables and handwritten or low-quality records can misassign fields. Always review extracted content before saving or sharing.
- Speech recognition quality depends on the microphone, language, ambient noise and device support.
- This prototype has received clinician feedback, but no formal clinical outcome study or independently validated diagnostic-accuracy claim is made.

## Troubleshooting

| Symptom | First checks |
|---|---|
| Offline answer unavailable | Confirm the Gemma model is fully downloaded, selected and initialised; check free storage and device RAM. |
| Guide citation missing | Confirm the official-guide package has been installed and the relevant document is present. |
| Cloud answer unavailable | Check connectivity, Groq key persistence, quota and selected online mode. |
| OCR result inaccurate | Retake a sharper, flatter image; verify row/column alignment before accepting it. |
| App crash or 16 KB warning | Capture Android version, ABI, page size and logcat when possible; do not assume all bundled native libraries are 16 KB compatible. |

## Version and development

**Current app version:** `1.0.0`

**Android application ID:** `com.arogyadoot.app`
**Project status:** Active prototype development and device validation.

Contributions and issue reports should include the app version, Android version, device type and reproducible steps—without secrets or private health records.

## Team

Developed by **Team AVM**:

- **Ekta Sikka** — Team lead and developer
- **Aryan Kalra** — Developer
- **Vaibhav Sikka** — Product research

Prototype reviewed by **Dr Manmohan Singh Rathore (MBBS, 5 years of experience)** and **Dhruv Punjani (third-year MBBS student)**. Their review should not be interpreted as regulatory approval or clinical efficacy validation.

## Licensing

No open-source licence is declared in this README. Please do not assume reuse rights beyond those expressly granted by the repository owner. Any third-party models, documents and libraries retain their own licences and terms.
