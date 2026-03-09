import json
import logging
import os
import traceback
import requests
import re
from pathlib import Path

from fastapi import FastAPI, Body, Request
from fastapi.responses import HTMLResponse, JSONResponse, FileResponse
from pydantic import BaseModel
from sklearn.metrics.pairwise import cosine_similarity
from sklearn.feature_extraction.text import TfidfVectorizer
import numpy as np

try:
    from sentence_transformers import SentenceTransformer
except ImportError:
    SentenceTransformer = None

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("voxenai")

app = FastAPI(title="VoxenAI Farm Assistant")

rag_data = None
texts = None
responses = None
metadata_list = None
vectorizer = None
tfidf_matrix = None

embedding_model = None
embeddings = None

BASE_DIR = Path(__file__).resolve().parent
RAG_FILE = Path(os.environ.get("RAG_FILE") or str(BASE_DIR / "coffee_rag_vectors.json"))

# ---------- OPENROUTER CONFIG (FIXED) ----------

OPENROUTER_API_URL = "https://openrouter.ai/api/v1/chat/completions"
OPENROUTER_API_KEY = os.environ.get("OPENROUTER_API_KEY")

# Stable OpenRouter model
OPENROUTER_MODEL = os.environ.get(
    "OPENROUTER_MODEL",
    "openai/gpt-3.5-turbo"  # Changed from mistral-7b-instruct:free
)

# ---------- REQUEST MODELS ----------


class ChatRequest(BaseModel):
    message: str
    latitude: float = None
    longitude: float = None
    location: str = None


class APIRequest(BaseModel):
    message: str = None
    data: list = None


# ---------- WEATHER FUNCTIONS ----------


def get_weather_data(latitude: float, longitude: float) -> dict:
    """Fetch weather data from Open-Meteo (free API, no key needed)"""
    try:
        url = f"https://api.open-meteo.com/v1/forecast"
        params = {
            "latitude": latitude,
            "longitude": longitude,
            "current": "temperature_2m,relative_humidity_2m,weather_code,precipitation,wind_speed_10m",
            "daily": "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,wind_speed_10m_max",
            "timezone": "auto",
            "forecast_days": 7
        }
        response = requests.get(url, params=params, timeout=10)
        if response.status_code == 200:
            return response.json()
    except Exception as e:
        logger.warning(f"Weather API error: {e}")
    return None


def get_coordinates_from_location(location: str) -> tuple:
    """Get coordinates from location name using geocoding"""
    try:
        url = f"https://geocoding-api.open-meteo.com/v1/search"
        params = {
            "name": location,
            "count": 1,
            "language": "en",
            "format": "json"
        }
        response = requests.get(url, params=params, timeout=10)
        if response.status_code == 200:
            data = response.json()
            if data.get("results"):
                result = data["results"][0]
                return result["latitude"], result["longitude"], result.get("admin1", result.get("country", ""))
    except Exception as e:
        logger.warning(f"Geocoding error: {e}")
    return None, None, None


def format_weather_context(weather_data: dict) -> str:
    """Format weather data into readable context"""
    if not weather_data:
        return ""
    
    try:
        current = weather_data.get("current", {})
        daily = weather_data.get("daily", {})
        
        context = "\n🌤️ **Current Weather Information:**\n"
        context += f"- Temperature: {current.get('temperature_2m', 'N/A')}°C\n"
        context += f"- Humidity: {current.get('relative_humidity_2m', 'N/A')}%\n"
        context += f"- Precipitation: {current.get('precipitation', 0)}mm\n"
        context += f"- Wind Speed: {current.get('wind_speed_10m', 'N/A')} km/h\n"
        
        if daily.get("temperature_2m_max"):
            context += f"\n📈 **7-Day Forecast:**\n"
            context += f"- High: {daily['temperature_2m_max'][0]}°C\n"
            context += f"- Low: {daily['temperature_2m_min'][0]}°C\n"
        
        return context
    except Exception as e:
        logger.warning(f"Weather formatting error: {e}")
        return ""


# ---------- LOAD RAG ----------


def load_rag():
    global rag_data, texts, responses, metadata_list, vectorizer, tfidf_matrix, embeddings, embedding_model

    if rag_data is not None:
        return

    logger.info("Loading RAG vectors from: %s", RAG_FILE)

    with open(RAG_FILE, "r", encoding="utf-8") as f:
        rag_data = json.load(f)

    texts = [item.get("text", "") for item in rag_data]
    responses = [item.get("response") or item.get("text", "") for item in rag_data]
    metadata_list = [item.get("metadata", {}) for item in rag_data]

    vectorizer = TfidfVectorizer()
    tfidf_matrix = vectorizer.fit_transform(texts)

    if rag_data and "embedding" in rag_data[0]:
        try:
            embeddings = np.vstack([np.array(item["embedding"], dtype=float) for item in rag_data])
        except Exception:
            embeddings = None
    else:
        embeddings = None

    if SentenceTransformer is not None and embeddings is not None:
        try:
            embedding_model = SentenceTransformer("all-MiniLM-L6-v2")
        except Exception:
            embedding_model = None


# ---------- GREETING & RESOURCE LINKS ----------


RESOURCE_LINKS = {
    "Uganda": {
        "ministry": "https://www.agriculture.go.ug",
        "ucda": "https://www.ugandacoffee.go.ug",
        "cocoa": "https://www.agriculture.go.ug",
        "contact": "info@ugandacoffee.go.ug"
    }
}

FRIENDLY_GREETINGS = {
    "initial": [
        "Hello! I'm your AI Farm Assistant, here to help with coffee and cocoa farming guidance from East Africa. What would you like to know?",
        "Welcome! I'm your agricultural advisor specializing in coffee and cocoa farming in East Africa. How can I assist you today?",
        "Hi there! I'm powered by Uganda's Ministry of Agriculture and UCDA expertise. Ask me anything about farming in East Africa!",
        "Greetings! I'm your farming companion with knowledge of official East African agricultural guidelines. What's on your mind?"
    ],
    "follow_up": [
        "Great question! Let me help you with that.",
        "I'm happy to help with that! Here's what I found:",
        "Excellent! Based on the official guidelines:",
        "Perfect! Let me share the recommended approach:"
    ],
    "unclear": [
        "I'm not sure how to answer that based on the available guidelines. Could you rephrase or ask about a specific farming practice?",
        "That's outside my expertise area. Please ask me about coffee, cocoa farming, or related agricultural topics in East Africa.",
        "I don't have detailed information on that. Would you like to know about coffee diseases, soil management, or other farming topics instead?"
    ]
}


def get_resource_links(country: str = "") -> str:
    """Generate resource links based on country"""
    if country not in RESOURCE_LINKS:
        return ""
    
    links = RESOURCE_LINKS[country]
    link_text = "\n\n📚 **Useful Resources:**\n"
    
    if country == "Uganda":
        link_text += f"- **Ministry of Agriculture:** https://www.agriculture.go.ug\n"
        link_text += f"- **Uganda Coffee Development Authority (UCDA):** https://www.ugandacoffee.go.ug\n"
        link_text += f"- **Email:** info@ugandacoffee.go.ug\n"
        link_text += f"- **Coffee Directory:** https://www.ugandacoffee.go.ug/coffee-directory\n"
    
    return link_text


def get_friendly_greeting() -> str:
    """Get a random friendly greeting"""
    import random
    return random.choice(FRIENDLY_GREETINGS["initial"])


def get_friendly_transition() -> str:
    """Get a friendly transition phrase for responses"""
    import random
    return random.choice(FRIENDLY_GREETINGS["follow_up"])


# ---------- OPENROUTER CALL ----------


def get_ai_response(context: str, message: str, country: str = "", ministry: str = "", weather_context: str = "") -> str:

    if not OPENROUTER_API_KEY:
        logger.warning("OPENROUTER_API_KEY not set")
        return "AI model API key not configured."

    headers = {
        "Authorization": f"Bearer {OPENROUTER_API_KEY}",
        "Content-Type": "application/json",
        "HTTP-Referer": "http://localhost:7860",
        "X-Title": "VoxenAI Farm Assistant",
    }

    country_context = f" (focusing on {country}" + (f" guidelines from {ministry}" if ministry else "") + ")" if country else ""
    
    weather_note = ""
    if weather_context:
        weather_note = "\n\nIMPORTANT: The farmer's current weather information is provided. Consider mentioning weather-specific advice (e.g., watering schedules, disease prevention during rainy seasons, etc.)."
    
    system_prompt = (
        "You are a friendly and knowledgeable agricultural advisor specializing in coffee and cocoa farming in East Africa. "
        "Your role is to provide helpful, practical guidance based on official agricultural guidelines and best practices. "
        f"Answer based on the context from official agricultural guidelines and manuals{country_context}. "
        "\n\nBe conversational and warm in your responses. Use phrases like 'Good question!', 'Great point!', or 'Let me share what the guidelines recommend...'. "
        "Always cite the country and ministry/organization when referencing specific guidelines. "
        "Provide actionable advice that farmers can implement immediately. "
        "If the context does not contain the answer, say you do not know and recommend consulting local agricultural offices. "
        "Encourage farmers to reach out to local extension offices for personalized assistance."
        f"{weather_note}"
    )

    user_content = f"Context from agricultural guidelines:\n{context}"
    if weather_context:
        user_content += f"\n\n{weather_context}"
    user_content += f"\n\nFarmer's Question:\n{message}"

    payload = {
        "model": OPENROUTER_MODEL,
        "messages": [
            {"role": "system", "content": system_prompt},
            {
                "role": "user",
                "content": user_content
            }
        ],
        "temperature": 0.3
    }

    try:

        response = requests.post(
            OPENROUTER_API_URL,
            headers=headers,
            json=payload,
            timeout=60
        )

        response.raise_for_status()

        data = response.json()

        ai_response = data["choices"][0]["message"]["content"].strip()
        
        # Add resource links if country is known
        if country:
            resource_links = get_resource_links(country)
            if resource_links:
                ai_response += resource_links

        return ai_response

    except Exception as e:

        logger.error("AI request failed: %s", str(e))

        try:
            logger.error("OpenRouter response: %s", response.text)
        except Exception:
            pass

        return f"Sorry, I'm having trouble connecting to the AI model right now. Error: {e}"


# ---------- CHAT ----------


@app.post("/chat")
def chat(request: ChatRequest):

    load_rag()

    user_msg = (request.message or "").strip()

    if not user_msg:
        return {"response": get_friendly_greeting()}

    # Enhanced greeting variants
    greetings = {"hi", "hello", "hey", "greetings", "good morning", "good afternoon", "good evening", "howdy", "welcome"}
    if user_msg.lower() in greetings:
        return {"response": get_friendly_greeting()}
    
    # Check for help/info requests
    help_keywords = {"help", "guide", "how to", "what is", "tell me", "explain", "tutorial", "tips"}
    if any(keyword in user_msg.lower() for keyword in help_keywords):
        pass  # Continue to full response
    
    if embeddings is not None and embedding_model is not None:

        query_emb = embedding_model.encode(user_msg)
        sims = cosine_similarity([query_emb], embeddings)[0]

    else:

        user_vec = vectorizer.transform([user_msg])
        sims = cosine_similarity(user_vec, tfidf_matrix)[0]

    top_k = 3
    top_idxs = np.argsort(sims)[::-1][:top_k]

    # Build context with metadata
    context_parts = []
    countries = set()
    ministries = set()
    
    for idx in top_idxs:
        idx_int = int(idx)
        text = responses[idx_int]
        meta = metadata_list[idx_int] if metadata_list else {}
        
        country = meta.get("country", "")
        ministry = meta.get("ministry", "")
        organization = meta.get("organization", "")
        crop = meta.get("crop", "")
        topic = meta.get("topic", "")
        
        if country:
            countries.add(country)
        if ministry:
            ministries.add(ministry)
        
        # Format context chunk with metadata
        source_info = ""
        if country or ministry or organization:
            source_parts = []
            if country:
                source_parts.append(f"Country: {country}")
            if ministry:
                source_parts.append(f"Ministry: {ministry}")
            if organization:
                source_parts.append(f"Organization: {organization}")
            if crop and crop != "coffee and cocoa":
                source_parts.append(f"Focus: {crop}")
            source_info = f"[{' | '.join(source_parts)}]\n"
        
        context_parts.append(f"{source_info}{text}")
    
    context = "\n\n---\n\n".join(context_parts)

    if len(context) > 3500:
        context = context[:3500]

    country_str = ", ".join(sorted(countries)) or "East Africa"
    ministry_str = ", ".join(sorted(ministries)) if ministries else ""

    # Get weather data if location is provided
    weather_context = ""
    latitude, longitude = request.latitude, request.longitude
    
    # If location name provided, convert to coordinates
    if request.location and not latitude:
        latitude, longitude, location_name = get_coordinates_from_location(request.location)
        logger.info(f"Location '{request.location}' resolved to ({latitude}, {longitude})")
    
    # Fetch weather if coordinates available
    if latitude and longitude:
        weather_data = get_weather_data(latitude, longitude)
        weather_context = format_weather_context(weather_data)
        logger.info(f"Weather context retrieved for ({latitude}, {longitude})")

    ai_response = get_ai_response(context, user_msg, country_str, ministry_str, weather_context)

    return {"response": ai_response}


# ---------- API SUPPORT ----------


@app.post("/api/chat")
def api_chat(request: APIRequest):

    msg = request.message

    if not msg and isinstance(request.data, list) and len(request.data) > 0:
        msg = request.data[0]

    if not msg:
        return {"error": "No message provided"}

    result = chat(ChatRequest(message=msg))

    return {"response": result["response"], "data": [result["response"]]}


@app.post("/api/predict")
def api_predict(request: APIRequest):

    msg = None

    if isinstance(request.data, list) and len(request.data) > 0:
        msg = request.data[0]

    if not msg:
        msg = request.message

    if not msg:
        return {"error": "No message provided"}

    result = chat(ChatRequest(message=msg))

    return {"data": [result["response"]]}


# ---------- UI ----------


@app.get("/", response_class=HTMLResponse)
def home():

    html_path = BASE_DIR / "floating_chat.html"

    if html_path.exists():
        return FileResponse(html_path)

    return HTMLResponse("<h1>VoxenAI Farm Assistant Running</h1>")


# ---------- HEALTH ----------


@app.get("/health")
def health():
    return {"message": "VoxenAI running"}


# ---------- STARTUP ----------


@app.on_event("startup")
def startup():

    load_rag()

    logger.info("RAG loaded with %d entries", len(texts))


# ---------- ERROR HANDLER ----------


@app.exception_handler(Exception)
async def catch_all_exceptions(request, exc):

    tb = traceback.format_exc()

    logger.error(tb)

    return JSONResponse(
        status_code=500,
        content={
            "error": str(exc),
            "traceback": tb
        },
    )


# ---------- LOCAL RUN ----------


if __name__ == "__main__":

    import uvicorn

    uvicorn.run("main:app", host="0.0.0.0", port=7860)