from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
import requests
import json
import time
import os

app = FastAPI()

# Enable CORS so your frontend can call the API from any origin
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# In-memory session memory (resets on server restart)
chat_memory = {}

# Use your Ollama Cloud API key
OLLAMA_API_KEY = "98818557107e4c4bb58b9e5d869d682b.9mRsxxetbanPaOn16bB2Z45K"
CLOUD_MODEL = "gemma3:4b"  # cloud model name

class ChatRequest(BaseModel):
    message: str
    session_id: str

@app.post("/chat_stream")
def chat_stream(req: ChatRequest):
    """
    Stream AI responses token by token for the frontend.
    """
    session_history = chat_memory.get(req.session_id, [])
    # Build prompt with session history
    prompt = "\n".join(session_history + [f"You: {req.message}\nBot:"])

    def generate():
        try:
            # Call Ollama Cloud API
            r = requests.post(
                "https://api.ollama.com/generate",
                headers={"Authorization": f"Bearer {OLLAMA_API_KEY}"},
                json={
                    "model": CLOUD_MODEL,
                    "prompt": prompt,
                    "stream": True,
                    "options": {
                        "num_predict": 200,
                        "temperature": 0.7,
                        "top_p": 0.9,
                        "top_k": 40
                    }
                },
                stream=True,
                timeout=120
            )

            bot_response = ""
            for line in r.iter_lines():
                if line:
                    try:
                        data = json.loads(line)
                        token = data.get("token", "")
                        if token:
                            bot_response += token
                            yield token
                            time.sleep(0.01)  # smooth typing effect
                    except json.JSONDecodeError:
                        continue

            # Update session memory (last 10 exchanges)
            session_history.append(f"You: {req.message}")
            session_history.append(f"Bot: {bot_response.strip()}")
            chat_memory[req.session_id] = session_history[-20:]

        except requests.exceptions.Timeout:
            yield "\n[AI took too long to respond]\n"
        except requests.exceptions.ConnectionError:
            yield "\n[AI service not available]\n"
        except Exception as e:
            yield f"\n[Unexpected error: {str(e)}]\n"

    return StreamingResponse(generate(), media_type="text/plain")