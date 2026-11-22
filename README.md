# Golfsvingen

En Android-app som filmar golfsvingen i 240 fps och sparar den automatiskt när du slår. Målet är att appen ska analysera svingen, visa de viktigaste felen och föreslå övningar. Version 0.3 hittar kroppen i varje bild, delar in svingen i faser, mäter de vanligaste felen och rangordnar dem, och visar för varje fel hur det ser ut på videon och vilka övningar som hjälper.

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

**Självutlösaren.** Tryck på **Filma om 5 s** och gå till bollen. Appen piper varje sekund, ger ett långt pip när den börjar filma och ett dubbelpip när den är klar. Väntetiden (3–30 s) och filmtiden (2–9 s) ställs in med − och +, och appen kommer ihåg dem. **Stoppa** avslutar filmningen i förtid. Ljudtriggern är avstängd medan självutlösaren går, så att pipen inte räknas som en träff. Pipen går på larmvolymen.

**Starta nu.** När någon annan filmar: tryck på **Starta nu** och sedan **Stoppa** efter svingen. Det som filmades däremellan sparas, högst 9 s.

**Ljus ger skärpa.** 240 fps betyder att varje bild exponeras högst 1/240 s, och kortare ju ljusare det är. Kroppen blir skarp utomhus i dagsljus. Inomhus behövs mycket ljus, och klubbhuvudet blir alltid suddigt vid träffen. Slutartiden står överst till höger. Är den längre än 1/400 s står det **Lite ljus**, och då blir händerna suddiga i nedsvingen.

Justera känsligheten med reglaget om appen missar träffar eller reagerar på annat ljud. Mätaren visar ljudnivån och den vita linjen gränsen.

## Var sparas svingarna?

I telefonens *Filmer/Golfsvingen*. De syns i Google Foto och Filer. Under **Mina svingar** kan du spela upp en sving i 1×, ¼, ⅛ eller 1/16 fart, stega bild för bild, dela den, spara en kopia till Google Drive med **Spara till…**, eller radera den.

**Radera utan att öppna.** Håll inne på en sving i listan för att markera den, och tryck på fler för att markera flera, eller **Alla**. **Radera** lägger dem i papperskorgen, där de finns kvar i 30 dagar. Det gör även **Radera** när en sving är öppen.

**Zooma.** Nyp med två fingrar på videon, upp till 6×, både när den spelar och när den står still. Inzoomad flyttar du bilden med ett finger, och ett dubbeltryck eller **Återställ** zoomar ut. Skelettet och markeringarna följer med. **Helskärm** döljer allt utom videon, med stegknappar, spela och reglage längst ned. Tryck på farten för att byta den.

**Klipp** tar bort det du inte vill spara, till exempel sekunderna när du står still. Dra i reglagets två ändar, eller stega fram till rätt bild och tryck **Start här** och **Slut här**. Medan du klipper spelas bara det valda avsnittet. **Spara klippet** sparar det som en ny fil och lägger originalet i papperskorgen, där det finns kvar i 30 dagar. Bilderna kopieras utan omkodning, så kvaliteten är oförändrad, men klippet börjar på närmaste nyckelbild, högst en halv sekund före den bild du valt.

När svingen är analyserad föreslår **Klipp** själv ett avsnitt, från en halv sekund före uppställningen till strax efter avslutet. **Föreslå** tar tillbaka förslaget om du ändrat det. Analysen och dina markeringar följer med till klippet, så det behöver inte analyseras igen.

## Jämföra två svingar

Tryck **Jämför** i en sving och välj den andra, eller markera två svingar i listan och tryck **Jämför**. **☆ Referens** märker din bästa sving. Den står då först när du väljer, och hamnar alltid till höger.

- **Sida vid sida** visar båda videorna. **Ovanpå** visar A, med B:s skelett och handbana i lila ovanpå. B flyttas och skalas så att höfterna i uppställningen hamnar på samma ställe och benen blir lika långa.
- **I takt** synkar faserna, så att båda når uppställningen, toppen, träffen och avslutet samtidigt, även om tempot skiljer sig. **Verklig tid** synkar bara träffen, så att skillnaden i tempo syns.
- Under videorna står båda svingarnas mätningar bredvid varandra. Tryck på A eller B uppe i videon för att öppna den svingen.

Med båda videorna i 1× fart kan telefonen behöva hoppa över bilder. I slow motion går de jämnt.

Filerna har verklig tid, 240 bilder per sekund. Andra spelare visar dem därför i normal fart.

## Analysen

När du öppnar en sving första gången letar appen upp kroppen i 120 bilder per sekund. Det tar en stund, mest för långa klipp, så klipp gärna svingen först. Resultatet sparas, och nästa gång visas det direkt. Allt sker i telefonen, med Googles kroppsmodell MediaPipe Pose (*heavy*). Ingen video lämnar telefonen.

- **Skelett.** Vänster sida är grön och höger blå. Den sida som är längst från kameran är mörkare och smalare och ligger bakom, så att det syns vad som är fram och bak. Den streckade gula linjen är ryggen, från höfternas mitt till axlarnas, och siffran nere till vänster är dess lutning från lodlinjen.
- **Handbana.** Händernas väg genom svingen. Den svaga linjen är hela banan, och den starka den del som spelats hittills.
- **Huvud.** Den streckade cirkeln är huvudets läge i klippets första bild, alltså uppställningen om klippet börjar där. Den gula pricken är huvudet nu.
- **Rita.** Linje, vinkel och cirkel. Vinkeln visar gradtalet. Markeringarna sparas med svingen. **Ångra** tar bort den senaste, och **Rensa** alla.

Vinklarna mäts i bilden och beror därför på var kameran står. Jämför helst svingar som är filmade från samma ställe.

Kroppsmodellen ser bara en platt bild. Appen rättar därför tre vanliga fel innan något ritas eller mäts:

- **Vänster och höger byter plats** mellan två bilder. De byts tillbaka.
- **En handled tappas.** Den kan skymmas av den andra handen, eller bli suddig i nedsvingen. Den följer då den andra handleden, eftersom händerna sitter ihop i greppet. Vilken handled som stämmer avgörs i första hand av underarmen. En tappad handled hamnar vid armbågen, så att underarmen blir för kort, medan modellen ofta är lika säker på den som på den rätta.
- **Korta luckor**, under 0,2 s, där en punkt är osäker, fylls i mellan de närmaste säkra bilderna.

Kroppen vrider sig mer än en kvarts varv både i backswingen och i avslutet. Då byter vänster och höger sida plats i bilden fast de inte är förväxlade. Framifrån hamnar till exempel den gröna axeln till vänster om den blå i avslutet, och benen korsar varandra när det bakre knät kommer fram. I en platt figur ser det ut som en omöjlig vridning. Därför ritas skelettet med kroppsmodellens uppskattade djup. Varje kroppsdel jämförs med samma del på andra sidan, och den som är längre bort blir svagare och hamnar bakom. Djupet jämnas ut över sju bilder, eftersom det hoppar mest av allt, särskilt för händerna. Svingar som analyserades före 0.4.9 saknar djup och analyseras om när de öppnas.

## Svingrapporten

Tryck på **Rapport** under videon. Appen hittar svingens faser, alltså uppställning, topp, träff och avslut, och du hoppar till dem med knapparna. Klippet behöver börja i uppställningen och sluta efter träffen.

Felen som mäts beror på var kameran står. Appen gissar det från bilden, och du kan ändra det:

| | Mätning | Fel när |
|---|---|---|
| Båda | Tempo, backswing mot nedsving | under 2,2 : 1, eller mer än 0,3 s stilla vid toppen |
| Båda | Huvudets höjd i träffen | mer än 8 cm ned eller 5 cm upp |
| Framifrån | Höften vid toppen | mer än 8 cm bort från målet |
| Framifrån | Huvudet i träffen | mer än 5 cm mot målet |
| Framifrån | Ryggen vid toppen | lutar mot målet (omvänd pivot) |
| Framifrån | Vikten i avslutet | höften mindre än 60 % av vägen mot främre foten |
| Framifrån | Ledarmen vid toppen | böjd mer än till 140° |
| Bakifrån | Höften i träffen | mer än 5 cm mot bollen (early extension) |
| Bakifrån | Ryggvinkeln i träffen | mer än 10° mer upprätt än i uppställningen |
| Bakifrån | Händerna i nedsvingen | mer än 4 cm utanför backswingen (över toppen) |

Tempot räknas från att händerna börjar röra sig till toppen, och från att de kommer upp i fart nedåt till träffen. Tiden däremellan visas som tid vid toppen, så att en paus inte ser ut som en långsam nedsving.

Felen rangordnas efter hur stora de är och hur grundläggande de är. Tryck på ett fel för att läsa vad det innebär, hur det ska se ut och vilka övningar som hjälper, med länkar till filmer. **Visa** hoppar till rätt bild och ritar referenslinjer, med uppställningen som en vit skugga så att det syns vad som rört sig. Rapporten krymper då till den mätningen, så att videon får plats. **◀ Förra** och **Nästa ▶** går mellan mätningarna, chipsen väljer vad som ritas, och **‹ Rapporten** visar hela rapporten igen.

Centimetrarna räknas fram med benlängden, höft till fotled, som skala. Den antas vara 80 cm. Gränserna är uppskattade och en utgångspunkt, inte en sanning. De justeras när fler svingar har testats.

**Dela mätdata** i rapporten sparar kroppspunkterna, med djup, som en CSV-fil i Hämtade filer/Golfsvingen, utan video, för att kunna justera mätningarna på en dator.

Analysen körs på grafikprocessorn. Fungerar inte den används processorn i stället, och det står vilken som användes överst till höger.

## Mikrofonen

Mikrofonen används bara för att känna igen bollträffen. Ljudet analyseras i 10 ms-bitar och sparas aldrig, och videon har inget ljud.

## Plan

| Version | Innehåll |
|---|---|
| 0.1 | Egen kamera i 240 fps, ljudtrigger med förtid, självutlösare, bibliotek, slow motion och bildstegning, klippning, spara till Drive |
| 0.2 | Kroppspunkter (MediaPipe) ritade på videon, och ritverktyg för linjer och vinklar |
| 0.3 | Rättning av kroppspunkterna, svingens faser, felen mäts och rangordnas, och varje fel får förklaring och övningar |
| 0.4 | Två svingar sida vid sida eller ovanpå varandra, i takt eller i verklig tid, referenssving, och förslag när man klipper |
| 0.5 | Vridningen av axlar och höfter, och i vilken ordning kroppen startar nedsvingen |
| 0.6 | Utvecklingen över tid, och övningar utifrån de senaste svingarna |
| Senare | Svingplanet bakifrån, inställning för längd och vänsterhänt, övningsläge med ljud |

## Bygga

GitHub Actions bygger appen vid varje push till `main` och lägger APK-filen som en release. Signeringsnyckeln ligger som hemligheterna `KEYSTORE_BASE64` och `KEYSTORE_PASSWORD`, så att varje version kan installeras över den förra. Utan dem signeras appen med en tillfällig nyckel.

Kotlin, Jetpack Compose, Camera2 (höghastighetsläge), MediaCodec och MediaMuxer för den löpande bufferten, och Media3 för uppspelningen.
