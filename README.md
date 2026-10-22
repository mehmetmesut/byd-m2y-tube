# M2Y Tube

**A touch-first, lightweight YouTube client for the BYD Seal U DM-i head unit.**
Developed by **Yeşil Dönüşüm Mühendisi - M2Y** and shared free for every BYD owner.

[Türkçe açıklama aşağıda](#türkçe) · Downloads: **[byd.mehmetmesut.com](https://byd.mehmetmesut.com)**

---

## Why it exists

The Seal U DM-i ships with DiLink 3.0 on Android 10 and no Google Play services, so the official YouTube app does not run. Existing TV clients work, but they are built for a remote control and for 4K televisions. M2Y Tube is tuned for the car instead:

| Head unit (measured over ADB) | What M2Y Tube does about it |
|---|---|
| Android 10 (API 29), arm64-v8a, no Google services | Built without Google Play services, Firebase or Crashlytics; arm64 only |
| Qualcomm SM6125 (8 cores, 4×1.8 + 4×2.0 GHz), Adreno 610, 8 GB RAM | No card shadows, zoom or rounded-corner clipping; thumbnails kept in memory; no services woken on boot, charging or screen-on |
| Hardware decoders: AVC, HEVC, VP9 (no AV1); 1080p up to ~120 fps | Quality capped at **1080p60**; AV1/HDR/2K/4K are never offered or chosen; **720p30 VP9** by default |
| 1920×1080 landscape touch screen at 240 dpi, no remote | Tap the picture to pause/resume, back arrow top-left, Home button, an **X** on every panel |
| Music while driving | Background audio on by default; the video keeps playing when you go to Home |

## Features

- Video quality between **360p and 1080p60**. Videos uploaded only below 360p play at their best available quality.
- Default quality 720p30 (VP9, hardware decoded), changeable per video.
- **Touch controls:** one tap pauses or resumes and shows the controls; the back arrow returns to the previous screen; the Home button opens the YouTube home page while playback continues; every settings, quality or statistics panel has an **X** that closes it in one tap.
- **Touch gestures:** the first tap only brings up the controls; swipe left/right for the previous/next video, up for suggestions, down to hide the controls; a tap beside an open side panel pauses/resumes.
- **Quality and speed lists** open right above their buttons; **Music** turns the video track off (audio only, less data and decoding) and shows the video's cover. Speeds: 0.1, 0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 4.
- Works signed out (no ads) or signed in to your YouTube account (TV code sign-in, no Google services needed).
- SponsorBlock and DeArrow support inherited from SmartTube.
- No ads, no analytics, no update checker; the app talks only to YouTube and the services you enable.

## Install (ADB over Wi-Fi)

1. Enable wireless ADB on the head unit (step-by-step guide on [byd.mehmetmesut.com](https://byd.mehmetmesut.com)).
2. On a computer with [Android platform-tools](https://developer.android.com/tools/releases/platform-tools):
   ```
   adb connect <car-ip>:5555
   adb install -r M2YTube_1.25.07.68.0_arm64-v8a.apk
   ```
3. Or use the one-click Windows installer from the download page.

## Build

```
git clone --recursive https://github.com/mehmetmesut/byd-m2y-tube.git
cd m2y-tube
# JDK 17, Android SDK platform 34, build-tools 30.0.3
./gradlew :smarttubetv:assembleStfdroidRelease
```
Outputs: `smarttubetv/build/outputs/apk/stfdroid/release/M2YTube_fdroid_<version>_{arm64-v8a,x86}.apk` (x86 is for the Android emulator). Signing uses a properties file outside the repository (`m2y.signingProps` in `local.properties`).

## Credits and licence

- Based on **[SmartTube](https://github.com/yuliskov/SmartTube)** by Yurii Liskov (yuliskov) and contributors, MIT licence. The original copyright notice is kept in [LICENSE](LICENSE).
- Includes ExoPlayer (Apache 2.0), Glide, Cronet and other libraries under their own licences.
- M2Y changes © 2026 Yeşil Dönüşüm Mühendisi - M2Y, released under the same MIT licence.
- **M2Y Tube is not affiliated with, endorsed by or sponsored by YouTube, Google or BYD.** "YouTube" and "BYD" are trademarks of their respective owners.

Sign-in uses the public OAuth client data that SmartTube publishes in its `constants.json`; no account data goes to M2Y.

---

## Türkçe

**BYD Seal U DM-i multimedya ekranı için dokunmatik öncelikli, hafif bir YouTube istemcisi.**
**Yeşil Dönüşüm Mühendisi - M2Y** tarafından kendi aracım için geliştirildi ve tüm BYD sahiplerinin kullanımına ücretsiz sunuldu.

### Neden?

Seal U DM-i, Google Play hizmetleri olmayan Android 10 (DiLink 3.0) kullanır; resmî YouTube uygulaması çalışmaz. TV istemcileri çalışır ama kumanda ve 4K televizyon için tasarlanmıştır. M2Y Tube aracın kendisine göre ayarlandı:

- **Donanım:** Qualcomm SM6125 (8 çekirdek), Adreno 610, 8 GB RAM, 1920×1080 / 240 dpi dokunmatik ekran, donanım kod çözücü AVC/HEVC/VP9 (AV1 yok).
- **Kalite:** en fazla **1080p60**, en az 360p, varsayılan **720p30 VP9**; AV1/HDR/2K/4K hiç sunulmaz.
- **Dokunmatik:** videoya dokun → duraklat/devam; sol üstte Geri; alt sağda Ev (video arka planda sürer); açılan her panelde **X**.
- **Hareketler:** ilk dokunuş yalnız denetimleri açar; sola/sağa kaydırma önceki/sonraki video, yukarı öneriler, aşağı denetimleri gizler; açık yan panelin yanına dokunmak oynat/duraklat.
- **Kalite ve hız listeleri** düğmenin hemen üstünde açılır; **Müzik** görüntüyü kapatır (yalnız ses, daha az veri ve çözme) ve videonun kapağını gösterir. Hızlar: 0,10 · 0,25 · 0,50 · 0,75 · 1 · 1,25 · 1,50 · 1,75 · 2 · 4.
- **Hız ve kaynak:** kart gölgesi/yakınlaştırması/yuvarlak köşe yok, küçük resimler bellekte, açılışta/şarjda uygulamayı uyandıran hizmet yok.
- **Gizlilik:** reklam, izleme, güncelleme denetimi yok; Google hizmeti gerekmez. Oturum açmadan reklamsız; isterseniz YouTube hesabınızla (TV kodu ile) oturum açabilirsiniz.

### Kurulum

ADB'yi açtığınız araca `adb connect <araç-ip>:5555` ve `adb install -r M2YTube_1.25.07.68.0_arm64-v8a.apk` ile ya da indirme sayfasındaki Windows kurulum aracıyla yükleyin: **[byd.mehmetmesut.com](https://byd.mehmetmesut.com)**

### Lisans

yuliskov'un **SmartTube** projesi tabanlıdır (MIT). M2Y değişiklikleri © 2026 Yeşil Dönüşüm Mühendisi - M2Y, aynı MIT lisansıyla. YouTube, Google ya da BYD ile bağlantılı değildir.
