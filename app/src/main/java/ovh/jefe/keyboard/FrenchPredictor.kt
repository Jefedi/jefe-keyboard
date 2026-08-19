package ovh.jefe.keyboard

/**
 * Prédicteur de mots en français — basé sur fréquence + préfixe.
 * Dictionnaire de ~250 mots les plus fréquents + complétion par préfixe.
 * Léger, fonctionne offline, pas de ML.
 */
class FrenchPredictor {

    // Top mots français par fréquence (formes fléchies)
    private val dictionary = listOf(
        "le", "la", "les", "un", "une", "des", "de", "du", "ce", "cette", "ces",
        "et", "ou", "mais", "donc", "or", "ni", "car", "que", "qui", "quoi",
        "je", "tu", "il", "elle", "on", "nous", "vous", "ils", "elles",
        "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa", "ses", "notre", "votre", "leur",
        "est", "sont", "était", "été", "être", "avoir", "ai", "as", "a", "avons", "avez", "ont",
        "fait", "faire", "fais", "faisons", "faites", "font",
        "peut", "peux", "pouvoir", "veux", "vouloir", "dois", "devoir",
        "va", "aller", "viens", "venir", "vois", "voir",
        "pas", "ne", "plus", "tres", "tres", "bien", "mal", "tout", "tous", "toute", "toutes",
        "pour", "par", "avec", "sans", "sur", "sous", "dans", "vers", "chez", "entre",
        "ici", "la", "ou", "quand", "comment", "pourquoi", "combien",
        "oui", "non", "peut-etre", "aussi", "encore", "toujours", "jamais",
        "homme", "femme", "enfant", "temps", "jour", "nuit", "vie", "monde",
        "chose", "autre", "meme", "tellement", "si", "tres",
        "aller", "partir", "venir", "reste", "rester",
        "bon", "bonne", "mauvais", "mauvaise", "grand", "grande", "petit", "petite",
        "nouveau", "nouvelle", "vieux", "vieille", "beau", "belle", "joli", "jolie",
        "premier", "premiere", "dernier", "derniere",
        "voir", "savoir", "pouvoir", "vouloir", "devoir", "falloir",
        "temps", "jour", "semaine", "mois", "annee", "heure", "minute",
        "maison", "appartement", "travail", "bureau", "ecole", "ville", "pays",
        "voiture", "train", "avion", "velo", "bus", "metro",
        "eau", "pain", "cafe", "the", "vin", "biere", "repas", "dejeuner", "diner",
        "amour", "ami", "amie", "famille", "pere", "mere", "frere", "soeur",
        "voici", "voila", "puis", "alors", "ensuite", "apres", "avant",
        "rien", "quelque", "quelques", "plusieurs", "chaque", "tous",
        "cela", "ca", "ceci", "celui", "celle", "ceux", "celles",
        "leur", "leurs", "notre", "nos", "votre", "vos",
        "beaucoup", "peu", "assez", "trop", "moins", "plus",
        "entre", "parmi", "malgre", "pendant", "depuis", "jusque",
        "comment", "quel", "quelle", "quels", "quelles",
        "se", "se", "me", "te", "lui", "leur", "y", "en",
        "sur", "sous", "devant", "derriere", "au-dessus", "au-dessous",
        "ainsi", "cependant", "néanmoins", "toutefois", "quoique",
        "voici", "voila", "parce", "puisque", "afin",
        "salut", "bonjour", "bonsoir", "merci", "pardon", "sil vous plait",
        "biensur", "evidemment", "certainement", "probablement", "peut-etre",
        "vraiment", "genial", "super", "cool", "parfait",
        "maintenant", "aujourdhui", "hier", "demain", "bientot", "tarde",
        "rapide", "lent", "facile", "difficile", "important", "possible",
        "ordinateur", "telephone", "message", "application", "internet", "email",
        "voiture", "route", " Rue", "centre", "gauche", "droite",
        "ouvrir", "fermer", "commencer", "finir", "continuer", "arreter",
        "parler", "dire", "demander", "repondre", "expliquer",
        "penser", "croire", "savoir", "comprendre", "apprendre",
        "aimer", "adorer", "detester", "preferer", "choisir",
        "manger", "boire", "dormir", "reveiller", "travailler",
        "jouer", "lire", "ecrire", "regarder", "ecouter",
        "acheter", "vendre", "payer", "couter", "economiser",
        "habiter", "vivre", "mourir", "naitre", "grandir",
        "appeler", "envoyer", "recevoir", "donner", "prendre",
        "trouver", "perdre", "chercher", "garder", "laisser",
        "monter", "descendre", "entrer", "sortir", "passer",
        "devenir", "rester", "sembler", "paraitre",
        "seul", "seule", "ensemble", "libre", "fort", "forte",
        "propre", "sale", "propre", "plein", "vide",
        "cle", "porte", "fenetre", "table", "chaise", "lit",
        "chien", "chat", "oiseau", "poisson", "animal",
        "rouge", "bleu", "vert", "jaune", "noir", "blanc", "gris", "orange",
        "numero", "nombre", "mot", "lettre", "phrase", "texte", "page",
        "question", "reponse", "probleme", "solution", "idee", "projet",
        "raison", "cause", "effet", "resultat", "but", "objectif",
        "cote", "cote", "face", "milieu", "bout", "begin",
        "argent", "prix", "euro", "centime", "facture", "compte",
        "ami", "amie", "copain", "copine", "voisin", "voisine",
        "docteur", "medecin", "hopital", "pharmacie", "maladie",
        "meteo", "soleil", "pluie", "neige", "vent", "nuage", "temperature",
        "musique", "chanson", "film", "video", "photo", "image",
        "sport", "match", "jeu", "jouet", "equipe",
        "route", "map", "destination", "voyage", "ticket"
    ).distinct().sorted()

    // Bigrammes simples pour la suggestion contextuelle
    private val commonAfter = mapOf(
        "je" to listOf("suis", "vais", "veux", "peux", "dois", "sais", "crois", "pense", "ai"),
        "tu" to listOf("es", "vas", "veux", "peux", "dois", "sais", "as", "as"),
        "il" to listOf("est", "va", "veut", "peut", "doit", "sait", "a", "faut"),
        "elle" to listOf("est", "va", "veut", "peut", "doit", "sait", "a"),
        "nous" to listOf("sommes", "allons", "voulons", "pouvons", "devons", "savons", "avons"),
        "vous" to listOf("etes", "allez", "voulez", "pouvez", "devez", "savez", "avez"),
        "ils" to listOf("sont", "vont", "veulent", "peuvent", "doivent", "savent", "ont"),
        "elles" to listOf("sont", "vont", "veulent", "peuvent", "doivent", "savent", "ont"),
        "le" to listOf("monde", "temps", "petit", "premier", "autre", "meme"),
        "la" to listOf("maison", "ville", "vie", "nuit", "femme", "porte"),
        "un" to listOf("peu", "jour", "homme", "autre", "moment", "truc"),
        "une" to listOf("femme", "fois", "autre", "idee", "heure", "chose"),
        "mon" to listOf("ami", "pere", "frere", "chien", "travail", "telephone"),
        "ton" to listOf("ami", "pere", "frere", "chien", "travail"),
        "son" to listOf("ami", "pere", "frere", "chien", "travail"),
        "ce" to listOf("qui", "que", "matin", "soir", "moment", "truc"),
        "ces" to listOf("jours", "choses", "gens", "moments"),
        "des" to listOf("gens", "choses", "fois", "amis", "jours"),
        "pas" to listOf("mal", "du", "de", "encore", "tout"),
        "tres" to listOf("bien", "mal", "bon", "mauvais", "joli", "important"),
        "pour" to listOf("toi", "moi", "que", "ca", "le", "la"),
        "avec" to listOf("toi", "moi", "ca", "le", "la", "nous"),
        "sans" to listOf("toi", "moi", "ca", "problème"),
    )

    /**
     * Get 3 suggestions based on current word buffer and last word.
     * @param currentWord partial word being typed (lowercase)
     * @param lastWord the word before the current one (lowercase, for context)
     * @return list of up to 3 suggestions
     */
    fun suggest(currentWord: String, lastWord: String? = null): List<String> {
        if (currentWord.isEmpty()) {
            // No partial word — suggest based on context
            if (lastWord != null) {
                val context = commonAfter[lastWord.lowercase()]
                if (context != null) {
                    return context.take(3)
                }
            }
            return emptyList()
        }

        val prefix = currentWord.lowercase()

        // 1. Exact match → high priority
        val exact = dictionary.filter { it == prefix }

        // 2. Prefix matches
        val prefixMatches = dictionary.filter { it.startsWith(prefix) && it != prefix }
            .sortedBy { it.length }

        // 3. Context suggestions matching prefix
        val contextSuggestions = if (lastWord != null) {
            commonAfter[lastWord.lowercase()]
                ?.filter { it.startsWith(prefix) }
                ?: emptyList()
        } else emptyList()

        // Combine: exact first, then context, then prefix matches
        return (exact + contextSuggestions + prefixMatches)
            .distinct()
            .take(3)
    }
}