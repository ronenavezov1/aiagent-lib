# llm-agent

ספריית Kotlin אחת לכל הפרויקטים: לקוחות LLM (Claude, Gemini, מודל מקומי) ומנוע סוכן
(`AiAgent`) שמפעיל כלים. המסגרת היא זו של date-with-roni, בלי שום דבר שקשור לפרויקט מסוים.

## להוסיף לפרויקט

לפי נתיב, בלי להעתיק jar. ב-`settings.gradle.kts` של הפרויקט:

```kotlin
includeBuild("../aiagent-lib")   // הנתיב היחסי לתיקייה הזו
```

וב-`build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.aiagent:llm-agent:0.1.0")
}
```

Gradle מחליף את התלות בקוד של התיקייה, ומקמפל אותה בכל בנייה של הפרויקט. שינוי כאן
נכנס לכל הפרויקטים בבנייה הבאה שלהם — בלי לפרסם ובלי להעתיק. גם IntelliJ פותח את הקוד
של הספרייה ישירות מהפרויקט, ואפשר לערוך אותה משם.

## מבנה

```
com.aiagent.lib
  agent/   AiAgent · AgentConfig · AgentHistory · AgentCapability (Tool, Action) · AgentStatus
  llm/     LlmClient ← BaseLlmClient ← AnthropicLlmClient · GoogleLlmClient · LocalLlmClient
           CountingLlmClient — עוטף כל לקוח וסופר טוקנים
  debug/   DebugLogger
```

## שימוש

```kotlin
val llm = AnthropicLlmClient(apiKey = key)                         // Claude Opus 5.5
// val llm = GoogleLlmClient(apiKey = key, fastModel = "<מודל>")   // Gemini
// val llm = LocalLlmClient(fastModel = "gemma2:27b")              // Ollama על המחשב

val agent = AiAgent(
    AgentConfig(
        agentId = "weather",
        llmClient = llm,
        systemPrompt = "אתה חזאי.",
        tools = mapOf(weather.name to weather),
    )
)
val answer = agent.processInput("מה מזג האוויר בתל אביב?")
```

כל ההגדרות עוברות בבנאי. הספרייה לא קוראת `.env` — זה התפקיד של הפרויקט.

## הספקים

| | FAST / SMART | חשיבה בין קריאות כלים | מה עוד |
|---|---|---|---|
| `AnthropicLlmClient` | Sonnet 5.5 (effort נמוך) / Opus 5.5 (effort גבוה) | התור של המודל חוזר אליו כמו שהוא | fallback בצד השרת כשמסווג הבטיחות מסרב |
| `GoogleLlmClient` | מודל אחר לכל דרגה | החתימה חוזרת על אותה קריאה | |
| `LocalLlmClient` | רק FAST — SMART זורק שגיאה | — | לא כל מודל מקומי תומך בכלים |

שלושתם עוברים דרך `BaseLlmClient`: ניסיונות חוזרים (`RetryPolicy`, ברירת מחדל 5) ובדיקה
שהתשובה לא ריקה.

## שגיאות

| | מתי | מה עושים |
|---|---|---|
| `LlmSendException` | הלקוח נכשל: רשת, מפתח, קרדיט — אחרי הניסיונות החוזרים | הספק לא זמין |
| `LlmNonFatalException` | הספק ענה, אבל אי אפשר להשתמש בתשובה (למשל סירוב) | לדלג על הבקשה הזו, הלקוח תקין |

## לוגים

SLF4J בלבד — הפרויקט בוחר מימוש (logback וכו'). לראות כל קלט ופלט של הסוכן:

```xml
<logger name="com.aiagent.lib" level="DEBUG"/>
```

## בדיקות

```bash
JAVA_HOME=$HOME/.jdks/openjdk-26.0.1 ./gradlew test
```

כל לקוח נבדק מול שרת מזויף מקומי, דרך ה-SDK האמיתי: בלי רשת ובלי מפתחות.

מול ספקים אמיתיים (עולה כמה סנטים), כל ספק שיש לו הגדרות בסביבה:

```bash
LIVE_LLM=1 ANTHROPIC_API_KEY=... OLLAMA=1 ./gradlew test --tests '*LiveLlmTest*'
```

## מה השתנה לעומת date-with-roni

- **package:** `com.dateroni.orchestrator` → `com.aiagent.lib`.
- **`BaseLlmClient`:**
  - מקבל `fastModel` ו-`smartModel` במקום `apiKey` ו-`modelName`. למודל מקומי אין מפתח.
  - `RetryPolicy` במקום מספרים קבועים.
  - `isPermanent` ניתן לדריסה, כך שכל ספק בודק לפי סוג השגיאה ב-SDK שלו ולא לפי טקסט.
  - ביטול (timeout) וסירוב לא נשלחים שוב.
- **`AnthropicLlmClient`:**
  - כשהמודל קורא לכלים, התור שלו נשמר ב-`thoughtSignature` וחוזר אליו כמו שהוא, כפי ש-Opus 5.5 דורש.
  - סירוב הופך ל-`LlmNonFatalException`.
  - הניסיונות החוזרים של ה-SDK כבויים, כדי שלא יהיו שתי שכבות של ניסיונות.
- **`DebugLogger`:** נשלט ברמת הלוג ולא ב-`RONI_DEBUG`.
