# VoxenAI Architecture & Data Flow

## System Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        USER INTERFACE LAYER                             │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  Webpage / Mobile Browser                                               │
│  ┌───────────────────────────────────┐                                 │
│  │   Floating Chat Button (˙✦)        │                                │
│  │  ┌──────────────────────────────┐ │                                │
│  │  │   Chat Panel                 │ │                                │
│  │  │  ┌──────────────────────────┐│ │                                │
│  │  │  │  Message History         ││ │                                │
│  │  │  │  [User]: How to improve  ││ │                                │
│  │  │  │   soil fertility?         ││ │                                │
│  │  │  │  [Bot]: According to Uganda││ │                                │
│  │  │  │   MAAIF guidelines...    ││ │                                │
│  │  │  └──────────────────────────┘│ │                                │
│  │  │  ┌──────────────────────────┐│ │                                │
│  │  │  │ [Input field] | [Send]   ││ │                                │
│  │  │  └──────────────────────────┘│ │                                │
│  │  └──────────────────────────────┘ │                                │
│  └───────────────────────────────────┘                                 │
│                       │                                                 │
│       HTTP POST /chat {message: "..."}                                 │
└───────────────────────┼──────────────────────────────────────────────┘
                        │
                        │ HTTP Response {response: "..."}
                        │
┌───────────────────────┼──────────────────────────────────────────────┐
│                       v                                                │
│          BACKEND API LAYER (FastAPI)                                   │
├──────────────────────────────────────────────────────────────────────┤
│                                                                        │
│  POST /chat Endpoint                                                 │
│  ├─ Receives: {"message": "What about cocoa?"}                      │
│  │                                                                    │
│  ├─ Load RAG Data ──┐                                               │
│  │                  v                                               │
│  │  coffee_rag_vectors.json (98 chunks)                            │
│  │  Each chunk has:                                                │
│  │    - text: Knowledge content                                    │
│  │    - metadata:                                                  │
│  │      * country: "Uganda"                                        │
│  │      * ministry: "Ministry of Agriculture..."                   │
│  │      * organization: "Uganda Coffee Development Authority"      │
│  │      * crop: "coffee and cocoa"                                │
│  │      * topic: "farming practices"                              │
│  │                                                                    │
│  ├─ Semantic Search                                                  │
│  │  TF-IDF or Sentence-Transformers                                 │
│  │  Find TOP-3 most relevant chunks                                 │
│  │  ├─ Chunk 1 (similarity: 0.95)                                  │
│  │  ├─ Chunk 2 (similarity: 0.87)                                  │
│  │  └─ Chunk 3 (similarity: 0.82)                                  │
│  │                                                                    │
│  ├─ Extract Metadata from Top-3 Chunks                              │
│  │  ├─ Countries: {Uganda}                                          │
│  │  ├─ Ministries: {Ministry of Agriculture...}                    │
│  │  ├─ Organizations: {Uganda Coffee Development Authority}        │
│  │  └─ Topics: {farming practices, cocoa production}               │
│  │                                                                    │
│  ├─ Build Context ──────────────────────────────────────────────┐  │
│  │                                                               │  │
│  │ Context = [                                                  │  │
│  │   "[Country: Uganda | Ministry: Ministry of Agriculture |   │  │
│  │    Organization: UCDA]",                                    │  │
│  │   "<Chunk 1 text>",                                         │  │
│  │   "---",                                                    │  │
│  │   "[Country: Uganda | Ministry: Ministry of Agriculture |   │  │
│  │    Organization: UCDA]",                                    │  │
│  │   "<Chunk 2 text>",                                         │  │
│  │   "---",                                                    │  │
│  │   "[Country: Uganda | Ministry: Ministry of Agriculture |   │  │
│  │    Organization: UCDA]",                                    │  │
│  │   "<Chunk 3 text>"                                          │  │
│  │ ]                                                             │  │
│  │                                                               │  │
│  └───────────────────────────────────────────────────────────┘  │
│  │                                                                    │
│  ├─ Prepare LLM Request                                              │
│  │  system_prompt = (                                               │
│  │    "You are an agricultural advisor...                          │
│  │     Answer based on official guidelines (focusing on Uganda     │
│  │     guidelines from Ministry of Agriculture, Animal Industry    │
│  │     and Fisheries).                                             │
│  │     Always cite the country and ministry..."                   │
│  │  )                                                               │
│  │                                                                    │
│  │  user_message = (                                                │
│  │    "Context from agricultural guidelines:\n{context}\n\n        │
│  │     Farmer's Question: What about cocoa?"                       │
│  │  )                                                               │
│  │                                                                    │
│  └─ Send to OpenRouter API ──────────────────────────────────────┐  │
│                                                                    │  │
└────────────────────────────────────────┬───────────────────────┘  │
                                         │                           │
                    ┌────────────────────┼────────────────────┐      │
                    │                    │                    │      │
                    v                    v                    v      │
        ┌──────────────────────────────────────────┐                │
        │        OpenRouter API (LLM Service)      │                │
        ├──────────────────────────────────────────┤                │
        │                                          │                │
        │  Model: Mistral 7B Instruct (or other)  │                │
        │  Temperature: 0.3                        │                │
        │  Max Tokens: default                     │                │
        │                                          │                │
        │  Processes:                              │                │
        │  1. System prompt (with country context) │                │
        │  2. User question                        │                │
        │  3. Context from RAG                     │                │
        │  4. Generates response                   │                │
        │                                          │                │
        └──────────────────────────────────────────┘                │
                         │                                          │
                    ┌────┴──────────────────┐                       │
                    v                       v                       │
        Response received             Return to user  ◄─────────────┘
        "According to Uganda's
         Ministry of Agriculture,
         Animal Industry and
         Fisheries (MAAIF),
         cocoa farming practices
         include..."
        
        │
        v
┌──────────────────────────────────────────────────────────────────┐
│            RESPONSE DELIVERY                                      │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│  HTTP Response                                                   │
│  {"response": "According to Uganda's Ministry..."}              │
│                                 │                                │
│                                 v                                │
│                        Update Chat UI                            │
│                        Display in chat window                    │
│                        With country/ministry context             │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘
```

## Data Flow Sequence

```
┌─────────────────────────────────────────────────────────────┐
│  STEP 1: USER INTERACTION                                    │
├─────────────────────────────────────────────────────────────┤
│  User clicks floating button → Types question → Clicks Send   │
│  Question: "How do I prevent coffee leaf rust in Uganda?"    │
└─────────────────────────────────────────────────────────────┘
                           │
                           v
┌─────────────────────────────────────────────────────────────┐
│  STEP 2: API REQUEST                                         │
├─────────────────────────────────────────────────────────────┤
│  POST /chat                                                  │
│  Content-Type: application/json                             │
│  Body: {                                                     │
│    "message": "How do I prevent coffee leaf rust in Uganda?" │
│  }                                                           │
└─────────────────────────────────────────────────────────────┘
                           │
                           v
┌─────────────────────────────────────────────────────────────┐
│  STEP 3: RAG RETRIEVAL                                       │
├─────────────────────────────────────────────────────────────┤
│  Load coffee_rag_vectors.json (98 chunks)                   │
│  Vectorize user question                                    │
│  Calculate similarity scores with all chunks                │
│  Select TOP-3 most similar chunks:                          │
│    1. Coffee disease management (sim: 0.94)                 │
│    2. Coffee pest control (sim: 0.89)                       │
│    3. Sustainable farming practices (sim: 0.84)             │
└─────────────────────────────────────────────────────────────┘
                           │
                           v
┌─────────────────────────────────────────────────────────────┐
│  STEP 4: METADATA EXTRACTION                                 │
├─────────────────────────────────────────────────────────────┤
│  From each TOP-3 chunk extract:                             │
│    Country: Uganda                                           │
│    Ministry: Ministry of Agriculture, Animal Industry       │
│              and Fisheries (MAAIF)                          │
│    Organization: Uganda Coffee Development Authority (UCDA) │
│    Crop: Coffee and Cocoa                                   │
│    Topic: Coffee disease management                         │
└─────────────────────────────────────────────────────────────┘
                           │
                           v
┌─────────────────────────────────────────────────────────────┐
│  STEP 5: CONTEXT BUILDING                                    │
├─────────────────────────────────────────────────────────────┤
│  Create context string with source labels:                  │
│                                                              │
│  [Country: Uganda | Ministry: MAAIF | Org: UCDA]           │
│  Content of top chunk #1 about coffee diseases...          │
│                                                              │
│  ---                                                         │
│                                                              │
│  [Country: Uganda | Ministry: MAAIF | Org: UCDA]           │
│  Content of top chunk #2 about pest control...             │
│                                                              │
│  ---                                                         │
│                                                              │
│  [Country: Uganda | Ministry: MAAIF | Org: UCDA]           │
│  Content of top chunk #3 about sustainability...            │
│                                                              │
│  Max length: 3500 characters                                │
└─────────────────────────────────────────────────────────────┘
                           │
                           v
┌─────────────────────────────────────────────────────────────┐
│  STEP 6: LLM REQUEST PREPARATION                             │
├─────────────────────────────────────────────────────────────┤
│  System Prompt:                                              │
│    "You are an agricultural advisor specializing in coffee  │
│     and cocoa farming in East Africa. Answer based on the  │
│     context from official agricultural guidelines and      │
│     manuals (focusing on Uganda guidelines from Ministry   │
│     of Agriculture, Animal Industry and Fisheries).        │
│     Always cite the country and ministry/organization      │
│     when referencing specific guidelines. If the context   │
│     does not contain the answer, say you do not know..."   │
│                                                              │
│  User Message:                                               │
│    "Context from agricultural guidelines:                  │
│     [Context chunks here...]                                │
│                                                              │
│     Farmer's Question:                                       │
│     How do I prevent coffee leaf rust in Uganda?"          │
│                                                              │
│  Temperature: 0.3 (low randomness for consistency)          │
└─────────────────────────────────────────────────────────────┘
                           │
                           v
┌─────────────────────────────────────────────────────────────┐
│  STEP 7: LLM GENERATION                                      │
├─────────────────────────────────────────────────────────────┤
│  OpenRouter API + Mistral 7B                                │
│  Generates response considering:                            │
│    - System prompt (cite Uganda+MAAIF)                      │
│    - Context (disease management guidelines)                │
│    - Question (coffee leaf rust prevention)                 │
│                                                              │
│  Generated Response:                                         │
│    "According to Uganda's Ministry of Agriculture, Animal  │
│     Industry and Fisheries (MAAIF) guidelines via the      │
│     Uganda Coffee Development Authority (UCDA), coffee     │
│     leaf rust prevention includes:                          │
│                                                              │
│     1. Selecting rust-resistant varieties validated by UCDA │
│     2. Maintaining proper plant spacing...                  │
│     [etc., with Minnesota ministry references]              │
│                                                              │
│     For specific implementation in your area, contact your  │
│     local UCDA or MAAIF agricultural extension office."     │
└─────────────────────────────────────────────────────────────┘
                           │
                           v
┌─────────────────────────────────────────────────────────────┐
│  STEP 8: API RESPONSE                                        │
├─────────────────────────────────────────────────────────────┤
│  HTTP 200 OK                                                │
│  {                                                           │
│    "response": "According to Uganda's Ministry of..."       │
│  }                                                           │
└─────────────────────────────────────────────────────────────┘
                           │
                           v
┌─────────────────────────────────────────────────────────────┐
│  STEP 9: UI DISPLAY                                          │
├─────────────────────────────────────────────────────────────┤
│  [Bot] According to Uganda's Ministry of Agriculture,       │
│       Animal Industry and Fisheries (MAAIF) guidelines...   │
│       [Full response displayed with formatting]             │
│                                                              │
│  User can continue asking follow-up questions               │
│  Each question goes through the same process                │
└─────────────────────────────────────────────────────────────┘
```

## Metadata Hierarchy

```
Knowledge Chunk
├─ Text Content
│  └─ Raw agricultural guidance
│
├─ Metadata
│  ├─ country: "Uganda"
│  │  └─ Used for country-specific responses
│  │
│  ├─ ministry: "Ministry of Agriculture, Animal Industry and F..."
│  │  └─ Used for source attribution & context
│  │
│  ├─ organization: "Uganda Coffee Development Authority (UCDA)"
│  │  └─ Used for organization-level authority
│  │
│  ├─ crop: "coffee and cocoa"
│  │  └─ Used for filtering & context specificity
│  │
│  ├─ topic: "value chain", "farming practices", etc.
│  │  └─ Used for categorization & search enhancement
│  │
│  ├─ source: "Uganda Coffee and Cocoa Directory"
│  │  └─ Used for full source citation
│  │
│  ├─ language: "en"
│  │  └─ Used for multi-language support
│  │
│  └─ page: 42
│     └─ Used for reference linking
```

## System Components

```
┌────────────────────────────────────────────────┐
│  Frontend Layer                                │
│  floating_chat.html (HTML/CSS/JavaScript)      │
│  - Chat UI                                     │
│  - Message display                             │
│  - API communication                           │
└────────────────────────────────────────────────┘
             ↕ HTTP
┌────────────────────────────────────────────────┐
│  API Layer                                     │
│  main.py (FastAPI)                             │
│  - /chat endpoint                              │
│  - /api/chat endpoint                          │
│  - /api/predict endpoint                       │
│  - /health endpoint                            │
└────────────────────────────────────────────────┘
             ↕ In-Memory
┌────────────────────────────────────────────────┐
│  Processing Layer                              │
│  - RAG Data Loading                            │
│  - Metadata Extraction                         │
│  - Context Building                            │
│  - Similarity Scoring                          │
└────────────────────────────────────────────────┘
             ↕ File I/O
┌────────────────────────────────────────────────┐
│  Data Layer                                    │
│  coffee_rag_vectors.json                       │
│  - 98 chunks with metadata                     │
│  - Country & ministry information              │
│  - Agricultural knowledge                      │
└────────────────────────────────────────────────┘
             ↕ HTTP
┌────────────────────────────────────────────────┐
│  LLM Layer                                     │
│  OpenRouter API                                │
│  - Mistral 7B Instruct                         │
│  - or configurable model                       │
└────────────────────────────────────────────────┘
```

---

**Diagram Version:** 1.0
**Updated:** March 2025
