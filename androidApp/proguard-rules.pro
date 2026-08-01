# Serwis referencjonowany po nazwie klasy z AndroidManifest.xml i przez MediaController/SessionToken.
-keep class app.nuta.android.PlaybackService { *; }
-keep class app.nuta.android.MainActivity { *; }

# Media3/Compose/AndroidX dostarczają własne consumer-rules.pro (AGP scala je automatycznie) —
# nie duplikujemy ich tutaj. Zostaje tylko to, co specyficzne dla naszego kodu.

# kotlinx.serialization.json jest tu używany wyłącznie do generycznego parsowania drzewa
# JsonElement/JsonObject/JsonArray (brak klas @Serializable w projekcie), więc nie potrzeba
# osobnych reguł na wygenerowane serializery.
