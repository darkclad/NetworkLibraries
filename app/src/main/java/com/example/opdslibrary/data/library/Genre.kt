package com.example.opdslibrary.data.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Genre entity with optional hierarchy support
 */
@Entity(
    tableName = "genres",
    indices = [
        Index("name"),
        Index("fb2Code"),
        Index("parentId")
    ],
    foreignKeys = [
        ForeignKey(
            entity = Genre::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class Genre(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,
    val parentId: Long? = null,        // For hierarchical genres
    val fb2Code: String? = null        // Original FB2 genre code (e.g., "sf_fantasy")
) {
    companion object {
        /**
         * Map FB2 genre codes to human-readable names
         */
        val FB2_GENRE_MAP = mapOf(
            // Science Fiction & Fantasy
            "sf" to "Science Fiction",
            "sf_action" to "Action SF",
            "sf_epic" to "Epic SF",
            "sf_heroic" to "Heroic SF",
            "sf_detective" to "Detective SF",
            "sf_cyberpunk" to "Cyberpunk",
            "sf_space" to "Space Opera",
            "sf_social" to "Social SF",
            "sf_horror" to "SF Horror",
            "sf_humor" to "SF Humor",
            "sf_fantasy" to "Fantasy",
            "sf_history" to "Historical SF",
            "sf_etc" to "Other SF",

            // Fantasy
            "fantasy" to "Fantasy",
            "fantasy_alt_hist" to "Alternative History",
            "fairy_tale" to "Fairy Tales",

            // Detective & Thriller
            "detective" to "Detective",
            "det_classic" to "Classic Detective",
            "det_police" to "Police Procedural",
            "det_action" to "Action Detective",
            "det_irony" to "Ironic Detective",
            "det_history" to "Historical Detective",
            "det_espionage" to "Espionage",
            "det_crime" to "Crime",
            "det_political" to "Political Detective",
            "det_maniac" to "Maniac",
            "det_hard" to "Hard-Boiled",
            "thriller" to "Thriller",

            // Prose
            "prose" to "Prose",
            "prose_classic" to "Classic Prose",
            "prose_history" to "Historical Prose",
            "prose_contemporary" to "Contemporary Prose",
            "prose_counter" to "Counterculture",
            "prose_rus_classic" to "Russian Classics",
            "prose_su_classics" to "Soviet Classics",

            // Romance
            "love" to "Romance",
            "love_contemporary" to "Contemporary Romance",
            "love_history" to "Historical Romance",
            "love_detective" to "Romantic Detective",
            "love_short" to "Short Romance",
            "love_erotica" to "Erotica",

            // Adventure
            "adventure" to "Adventure",
            "adv_western" to "Western",
            "adv_history" to "Historical Adventure",
            "adv_indian" to "Indian Adventures",
            "adv_maritime" to "Maritime Adventures",
            "adv_geo" to "Travel Adventures",
            "adv_animal" to "Animal Stories",

            // Children
            "child" to "Children's",
            "child_tale" to "Children's Tales",
            "child_verse" to "Children's Verse",
            "child_prose" to "Children's Prose",
            "child_sf" to "Children's SF",
            "child_det" to "Children's Detective",
            "child_adv" to "Children's Adventure",
            "child_education" to "Educational",

            // Poetry & Drama
            "poetry" to "Poetry",
            "dramaturgy" to "Drama",

            // Antique
            "antique" to "Antique Literature",
            "antique_ant" to "Ancient Literature",
            "antique_european" to "European Antique",
            "antique_russian" to "Russian Antique",
            "antique_east" to "Eastern Antique",
            "antique_myths" to "Myths & Legends",

            // Science & Education
            "science" to "Science",
            "sci_history" to "History",
            "sci_psychology" to "Psychology",
            "sci_culture" to "Cultural Studies",
            "sci_religion" to "Religious Studies",
            "sci_philosophy" to "Philosophy",
            "sci_politics" to "Politics",
            "sci_business" to "Business",
            "sci_juris" to "Law",
            "sci_linguistic" to "Linguistics",
            "sci_medicine" to "Medicine",
            "sci_phys" to "Physics",
            "sci_math" to "Mathematics",
            "sci_chem" to "Chemistry",
            "sci_biology" to "Biology",
            "sci_tech" to "Technology",

            // Computers
            "comp" to "Computers",
            "comp_www" to "Internet",
            "comp_programming" to "Programming",
            "comp_soft" to "Software",
            "comp_db" to "Databases",
            "comp_osnet" to "OS & Networks",
            "comp_hard" to "Hardware",

            // Reference
            "ref" to "Reference",
            "ref_encyc" to "Encyclopedia",
            "ref_dict" to "Dictionary",
            "ref_ref" to "Reference Book",
            "ref_guide" to "Guide",

            // Nonfiction
            "nonf" to "Nonfiction",
            "nonf_biography" to "Biography",
            "nonf_publicism" to "Journalism",
            "nonf_criticism" to "Criticism",
            "design" to "Design",

            // Religion
            "religion" to "Religion",
            "religion_rel" to "Religious Literature",
            "religion_esoterics" to "Esoterics",
            "religion_self" to "Self-Help",

            // Humor
            "humor" to "Humor",
            "humor_anecdote" to "Anecdotes",
            "humor_prose" to "Humorous Prose",
            "humor_verse" to "Humorous Verse",

            // Home
            "home" to "Home & Family",
            "home_cooking" to "Cooking",
            "home_pets" to "Pets",
            "home_crafts" to "Crafts",
            "home_entertain" to "Entertainment",
            "home_health" to "Health",
            "home_garden" to "Garden",
            "home_diy" to "DIY",
            "home_sport" to "Sports",
            "home_sex" to "Relationships"
        )

        /**
         * Get human-readable name for FB2 genre code
         */
        fun getNameForFb2Code(code: String): String {
            return FB2_GENRE_MAP[code.lowercase()] ?: code.replace("_", " ")
                .replaceFirstChar { it.uppercase() }
        }
    }
}
