# Skins

Named colour packs, not separate apps. Screens read `IhfThemeAccess`.

| Id | Name | Notes |
| --- | --- | --- |
| `ihf_night` | IHF Night | Default for the `ihf` flavor |
| `carbon_signal` | Carbon Signal | Default for the `community` flavor |
| `ihf_mist` | IHF Mist | Light pack |

Registry: `android/app/src/main/java/net/ithandsfree/softphone/ui/theme/SoftphoneSkin.kt`.

Switch in the app under Settings → Appearance, or set `default_skin` in BFF `config.php` (`GET /v1/health` → `branding.default_skin`). The app applies the BFF skin until the user picks one.

HTML lab:

```bash
cd skins-preview
python3 -m http.server 8765
```
