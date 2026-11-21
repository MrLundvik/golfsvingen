package io.github.mrlundvik.golfsvingen.analysis

/** En övning. [search] är en engelsk sökning på YouTube, där det finns flest filmer om övningen. */
class Drill(val name: String, val how: String, val search: String? = null)

/** Vad ett fel innebär, hur det ska se ut och vad som hjälper. */
class Advice(
    val faultTitle: String,
    val why: String,
    val ideal: String,
    val drills: List<Drill>,
    val caveat: String? = null,
)

object Coach {
    private val countTempo = Drill(
        "Räkna tre–ett",
        "Säg ”ett, två, tre” genom backswingen och ”ett” i träffen. Börja med halvsvingar och öka sedan.",
        "golf tempo drill 3 to 1",
    )
    private val toneTempo = Drill(
        "Svinga till toner",
        "Använd en tempoapp eller ljudfil med tre toner, för start, topp och träff, i takten 3:1. Tour Tempo är den mest kända.",
        "tour tempo golf drill",
    )
    private val feetTogether = Drill(
        "Fötterna ihop",
        "Slå halvslag med fötterna ihop i ungefär 70 % fart. Har du för bråttom tappar du balansen direkt.",
        "feet together golf drill",
    )
    private val stepDrill = Drill(
        "Stegövningen",
        "Börja med fötterna ihop. Kliv ut mot målet med främre foten precis när backswingen är klar, och slå.",
        "golf step drill weight shift",
    )
    private val wallDrill = Drill(
        "Stjärten mot väggen",
        "Ställ dig i uppställningen med stjärten lätt mot en vägg. Svinga långsamt så att främre skinkan fortfarande rör vid väggen i träffen.",
        "early extension wall drill golf",
    )

    val TEMPO_FAST = Advice(
        faultTitle = "Bråttom i backswingen",
        why = "Backswingen går fort jämfört med nedsvingen. Kroppen hinner inte vrida klart, och armarna tar över från toppen.",
        ideal = "Backswingen tar ungefär tre gånger så lång tid som nedsvingen. Hos proffs är det ofta 0,7–1,0 s upp och 0,25–0,33 s ned.",
        drills = listOf(countTempo, toneTempo, feetTogether),
    )
    val TEMPO_SLOW = Advice(
        faultTitle = "Lång paus vid toppen",
        why = "Nedsvingen kommer långt efter backswingen. En lång paus eller en seg start nedåt gör det svårt att få rytm och fart i träffen.",
        ideal = "Backswingen tar ungefär tre gånger så lång tid som nedsvingen. Hos proffs är det ofta 0,7–1,0 s upp och 0,25–0,33 s ned.",
        drills = listOf(countTempo, toneTempo),
    )

    private val headWall = Drill(
        "Huvudet mot väggen",
        "Ställ dig i uppställningen med pannan nästan mot en vägg, eller mot ett paraply som någon håller. Gör långsamma svingar utan att pannan går mot eller från väggen.",
        "golf head against wall drill",
    )
    private val shadow = Drill(
        "Skuggan",
        "Ställ dig med solen i ryggen så att huvudets skugga hamnar på bollen. Håll skuggan still till efter träffen.",
        "golf shadow drill keep head still",
    )
    private val circle = Drill(
        "Cirkeln i appen",
        "Filma några svingar med Huvud påslaget. Den gula pricken ska stanna inne i cirkeln till efter träffen.",
    )
    val HEAD_DOWN = Advice(
        faultTitle = "Huvudet sjunker",
        why = "Huvudet sjunker mot bollen mellan uppställningen och träffen. Avståndet till bollen ändras, och slagen blir tjocka eller toppade.",
        ideal = "Huvudet håller ungefär samma höjd genom svingen. Några centimeter nedåt i nedsvingen är normalt, men inte mer.",
        drills = listOf(headWall, shadow, circle),
    )
    val HEAD_UP = Advice(
        faultTitle = "Huvudet höjs",
        why = "Huvudet går upp före träffen, oftast för att kroppen reser sig. Då lyfts händerna och klubban också, och slagen blir tunna eller toppade.",
        ideal = "Huvudet håller ungefär samma höjd genom svingen och reser sig först efter träffen.",
        drills = listOf(headWall, shadow, circle),
    )

    val SWAY = Advice(
        faultTitle = "Höften glider bakåt",
        why = "Höften glider åt sidan bort från målet i backswingen i stället för att vrida. Den måste då glida tillbaka i nedsvingen, och träffen blir svår att upprepa.",
        ideal = "Höften vrider runt sig själv och stannar ungefär där den var. Vikten går in mot insidan av bakre foten, aldrig utanför den.",
        drills = listOf(
            Drill(
                "Pinne vid bakre höften",
                "Stick en pinne eller ett paraply lodrätt i marken precis utanför bakre höften. Gör backswingen utan att höften trycker mot den.",
                "golf sway drill alignment stick hip",
            ),
            Drill(
                "Bakre knät kvar",
                "Behåll böjen i bakre knät genom hela backswingen. Känn trycket på insidan av bakre foten.",
                "golf drill maintain trail knee flex backswing",
            ),
        ),
    )

    val HEAD_SLIDE = Advice(
        faultTitle = "Huvudet glider mot målet",
        why = "Huvudet och överkroppen glider mot målet före träffen. Klubban kommer då efter, och slaget tappar både kraft och riktning.",
        ideal = "Höften förs mot målet i nedsvingen, men huvudet stannar bakom bollen till efter träffen.",
        drills = listOf(
            Drill(
                "Pinne vid huvudet",
                "Låt någon hålla en pinne lodrätt vid främre örat i uppställningen. Huvudet ska inte passera den förrän bollen är träffad.",
                "golf drill stay behind the ball alignment stick head",
            ),
            Drill(
                "Bakre hälen kvar",
                "Låt bakre hälen stanna i marken tills bollen är träffad. Det håller överkroppen bakom bollen.",
                "golf drill trail heel down through impact",
            ),
            Drill(
                "Bollens baksida",
                "Håll blicken på bollens bakre kant till efter träffen.",
            ),
        ),
    )

    val REVERSE_PIVOT = Advice(
        faultTitle = "Omvänd pivot",
        why = "Vid toppen lutar överkroppen mot målet i stället för bort från det. Vikten hamnar på främre foten i backswingen och flyttas bakåt i nedsvingen, alltså tvärtemot vad den ska.",
        ideal = "Vid toppen ligger axlarnas mitt bakom höfternas, längre från målet, och vikten är på insidan av bakre foten.",
        drills = listOf(
            Drill(
                "Klubba över axlarna",
                "Håll en klubba tvärs över axlarna och vrid till toppen. Klubbans främre ände ska peka mot bollen eller strax bakom den, utan att du lutar mot målet.",
                "golf reverse pivot drill club across shoulders",
            ),
            stepDrill,
            Drill(
                "Bröstet bort från målet",
                "Känn att bakre axeln går bakåt och bröstet pekar bort från målet vid toppen.",
                "golf drill turn behind the ball",
            ),
        ),
    )

    val WEIGHT_BACK = Advice(
        faultTitle = "Vikten stannar på bakre foten",
        why = "I avslutet är höften kvar över bakre foten. Vikten har inte flyttats till främre benet, och slaget tappar kraft.",
        ideal = "I avslutet står du i balans på främre foten med höften över den, bakre foten på tån och bältesspännet mot målet.",
        drills = listOf(
            Drill(
                "Håll avslutet",
                "Stå kvar i avslutet i tre sekunder efter varje slag, med bakre foten på tån och vikten på främre foten.",
                "golf hold your finish balance drill",
            ),
            stepDrill,
            Drill(
                "Gå efter slaget",
                "Låt bakre foten fortsätta ett steg mot målet efter träffen.",
                "gary player walk through drill",
            ),
        ),
    )

    val BENT_LEAD_ARM = Advice(
        faultTitle = "Ledarmen böjs vid toppen",
        why = "Ledarmen böjs mycket vid toppen. Svingbågen blir kortare och varierar, och det blir svårt att träffa på samma ställe varje gång.",
        ideal = "Ledarmen är nästan rak vid toppen, men inte låst. Backswingens längd kommer från att kroppen vrider, inte från att armen böjs.",
        caveat = "Vinkeln ser mindre ut än den är om armen pekar mot eller från kameran.",
        drills = listOf(
            Drill(
                "Kortare backswing",
                "Stoppa backswingen där ledarmen börjar böjas. Med bra vridning räcker den längden.",
                "golf drill straight lead arm shorter backswing",
            ),
            Drill(
                "Bred takeaway",
                "För klubban lågt och rakt bakåt med händerna långt från bröstet, så att svingen får bredd.",
                "golf wide takeaway drill",
            ),
        ),
    )

    val EARLY_EXTENSION = Advice(
        faultTitle = "Höften går mot bollen",
        why = "Höften rör sig mot bollen i nedsvingen, så kallad early extension. Då får armarna inte plats, och slagen går snett eller blir tunna.",
        ideal = "Höften håller avståndet till bollen genom träffen. Stjärten stannar på linjen från uppställningen eller går lite bakåt.",
        drills = listOf(
            wallDrill,
            Drill(
                "Stol bakom stjärten",
                "Ställ en stol så att stjärten nuddar den i uppställningen, och behåll kontakten genom träffen.",
                "early extension chair drill golf",
            ),
            Drill(
                "Sätt dig i nedsvingen",
                "Känn att du sätter dig lite, med knäna isär, i början av nedsvingen innan du vrider.",
                "golf squat in downswing drill",
            ),
        ),
    )

    val STAND_UP = Advice(
        faultTitle = "Kroppen reser sig före träffen",
        why = "Ryggen är mer upprätt i träffen än i uppställningen. Kroppen reser sig, och händerna måste rädda slaget i sista stund.",
        ideal = "Ryggvinkeln är ungefär densamma i träffen som i uppställningen, och bröstet är kvar över bollen till efter träffen.",
        drills = listOf(
            Drill(
                "Klubba längs ryggen",
                "Håll en klubba längs ryggen så att den rör vid bakhuvudet, skulderbladen och korsryggen. Vrid fram och tillbaka utan att tappa kontakten.",
                "golf spine angle drill club along back",
            ),
            Drill(
                "Bröstet över bollen",
                "Slå halvslag och håll bröstet över bollen tills den är träffad.",
                "golf drill chest over the ball",
            ),
            wallDrill,
        ),
    )

    val OVER_THE_TOP = Advice(
        faultTitle = "Över toppen",
        why = "I början av nedsvingen går händerna utåt mot bollen i stället för nedåt. Klubban kommer då utifrån och in, och slagen blir slicar eller drag.",
        ideal = "Händerna faller först nedåt från toppen och kommer på eller innanför backswingens bana. Den orange nedsvingen ska ligga under den gröna backswingen.",
        drills = listOf(
            Drill(
                "Hinder utanför bollen",
                "Lägg ett huvudskydd strax utanför och bakom bollen. Svinga utan att träffa det.",
                "over the top drill headcover outside ball",
            ),
            Drill(
                "Pausa och släpp",
                "Pausa vid toppen, låt händerna sjunka mot bakre höften och vrid först därefter.",
                "golf pause at the top drop drill",
            ),
            Drill(
                "Bakre armbågen mot höften",
                "Känn att bakre armbågen går ned mot höften i början av nedsvingen.",
                "golf drill trail elbow to hip downswing",
            ),
        ),
    )
}
