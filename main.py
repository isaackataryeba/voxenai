import json
import numpy as np
import requests
from sentence_transformers import SentenceTransformer
from fastapi import FastAPI
from pydantic import BaseModel
from typing import List

# Initialize the embedding model
model = SentenceTransformer("all-MiniLM-L6-v2")

# Load the coffee knowledge base (RAG)
with open("coffee_rag_vectors.json", "r", encoding="utf-8") as f:
    vector_db = json.load(f)

# FastAPI app setup
app = FastAPI()

# Define the request structure
class QueryRequest(BaseModel):
    message: str
    session_id: str

# Ollama Cloud API configuration
OLLAMA_API_URL = "https://api.ollama.com/v1/chat/completions"
OLLAMA_API_KEY = "98818557107e4c4bb58b9e5d869d682b.9mRsxxetbanPaOn16bB2Z45K"  # Replace with your actual API key

# Model to use (example: phi3:mini)
CLOUD_MODEL = "phi3:mini"  

# Cosine similarity function for RAG (Retrieval-Augmented Generation)
def cosine_similarity(a, b):
    return np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b))

# Search function to find relevant chunks from the RAG vector DB
def search_knowledge(query_embedding, db, top_k=3):
    scores = []
    for item in db:
        score = cosine_similarity(query_embedding, item["embedding"])
        scores.append((score, item["text"]))
    scores.sort(reverse=True, key=lambda x: x[0])
    return [text for _, text in scores[:top_k]]

# Function to get AI response from Ollama Cloud
def get_ai_response(context, message):
    headers = {
        "Authorization": f"Bearer {OLLAMA_API_KEY}",
        "Content-Type": "application/json",
    }

    data = {
        "model": CLOUD_MODEL,  # Replace this with the model you are using
        "messages": [
            {"role": "system", "content": "You are a helpful assistant."},
            {"role": "user", "content": message},
            {"role": "assistant", "content": context}
        ],
        "options": {
            "num_predict": 200,
            "temperature": 0.7,
            "top_p": 0.9,
            "top_k": 40
        }
    }

    r = requests.post(OLLAMA_API_URL, headers=headers, json=data, timeout=60)
    response = r.json()
    return response.get("completion") or response.get("text") or str(response)

# Chat endpoint for FastAPI
@app.post("/chat_stream")
async def chat_stream(query: QueryRequest):
    # Step 1: Embed the user's query to get the embedding
    query_embedding = model.encode(query.message)

    # Step 2: Search for the most relevant chunks from the RAG vector DB
    relevant_chunks = search_knowledge(query_embedding, vector_db)

    # Step 3: Combine the relevant chunks into context
    context = "\n".join(relevant_chunks)

    # Step 4: Get AI response from Ollama
    ai_response = get_ai_response(context, query.message)

    # Return the AI response
    return {"response": ai_response}
