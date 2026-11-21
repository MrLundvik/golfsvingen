# Golfsvingen

En Android-app som filmar golfsvingen i 240 fps och sparar den automatiskt när du slår. Målet är att appen ska analysera svingen, visa de viktigaste felen och föreslå övningar. Version 0.1 filmar, sparar och spelar upp i slow motion. Analysen kommer i nästa steg.

Byggd för Pixel 10 Pro, men fungerar på telefoner vars bakre kamera har ett höghastighetsläge.

## Installera

1. Öppna [senaste versionen](https://github.com/MrLundvik/golfsvingen/releases/latest) i Chrome på telefonen.
2. Tryck på filen `Golfsvingen-v0.1.x.apk` under *Assets*.
3. Tillåt Chrome att installera appar när telefonen frågar, och installera.

En ny version installeras på samma sätt, ovanpå den gamla. Svingarna ligger kvar.

## Så filmar du

1. Ställ telefonen stadigt i höfthöjd, 3–4 meter bort, så att hela kroppen och klubban syns. Filma rakt framifrån eller bakifrån längs siktlinjen. Lodlinjen i mitten hjälper dig att rikta.
2. Slå. Appen filmar hela tiden och behåller de senaste sekunderna. När den hör bollträffen sparas 3,5 sekunder före och 1,5 sekunder efter, alltså hela svingen.
3. Utan boll: klappa i händerna direkt efter svingen. Eller tryck på en volymknapp, eller på en Bluetooth-fjärrutlösare, så sparas de senaste 5 sekunderna.

**Ljus ger skärpa.** 240 fps betyder att varje bild exponeras högst 1/240 s, och kortare ju ljusare det är. Kroppen blir skarp utomhus i dagsljus. Inomhus behövs mycket ljus, och klubbhuvudet blir alltid suddigt vid träffen.

Justera känsligheten med reglaget om appen missar träffar eller reagerar på annat ljud. Mätaren visar ljudnivån och den vita linjen gränsen.

## Var sparas svingarna?

I telefonens *Filmer/Golfsvingen*. De syns i Google Foto och Filer. Under **Mina svingar** kan du spela upp en sving i 1×, ¼, ⅛ eller 1/16 fart, stega bild för bild, dela den, spara en kopia till Google Drive med **Spara till…**, eller radera den.

Filerna har verklig tid, 240 bilder per sekund. Andra spelare visar dem därför i normal fart.

## Mikrofonen

Mikrofonen används bara för att känna igen bollträffen. Ljudet analyseras i 10 ms-bitar och sparas aldrig, och videon har inget ljud.

## Plan

| Version | Innehåll |
|---|---|
| 0.1 | Egen kamera i 240 fps, ljudtrigger med förtid, bibliotek, slow motion och bildstegning, spara till Drive |
| 0.2 | Kroppspunkter (MediaPipe) ritade på videon, och ritverktyg för linjer och vinklar |
| 0.3 | Svingens faser hittas automatiskt, felen mäts och rangordnas, och varje fel får övningar |
| 0.4 | Två svingar sida vid sida, och utvecklingen över tid |

## Bygga

GitHub Actions bygger appen vid varje push till `main` och lägger APK-filen som en release. Signeringsnyckeln ligger som hemligheterna `KEYSTORE_BASE64` och `KEYSTORE_PASSWORD`, så att varje version kan installeras över den förra. Utan dem signeras appen med en tillfällig nyckel.

Kotlin, Jetpack Compose, Camera2 (höghastighetsläge), MediaCodec och MediaMuxer för den löpande bufferten, och Media3 för uppspelningen.
