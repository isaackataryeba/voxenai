# VoxenAI Farm Assistant - Floating Chatbot

Your AI-powered agricultural chatbot is now enhanced with **country and ministry-specific knowledge** for East African coffee and cocoa farming guidelines.

## 🎯 What You Have

A floating button chatbot that:
- ✓ Understands East African coffee and cocoa farming
- ✓ References Uganda's Ministry of Agriculture guidelines
- ✓ Cites official sources (UCDA, MAAIF)
- ✓ Provides country-specific agricultural advice
- ✓ Works with OpenRouter LLM (Mistral 7B)
- ✓ Ready to expand with more countries

## 🚀 Quick Start (2 minutes)

1. **Set your API key:**
   ```bash
   $env:OPENROUTER_API_KEY="your_key_from_https://openrouter.ai"
   ```

2. **Run the app:**
   ```bash
   python main.py
   ```

3. **Chat:**
   - Open http://localhost:7860
   - Click the floating button (˙✦)
   - Ask about coffee/cocoa farming
   - Get Uganda-specific guidance!

## 📚 Documentation

| Document | Purpose |
|----------|---------|
| [QUICKSTART.md](QUICKSTART.md) | 2-minute setup & common issues |
| [SETUP_GUIDE.md](SETUP_GUIDE.md) | Complete setup, deployment, extension |
| [ARCHITECTURE.md](ARCHITECTURE.md) | System design & data flow diagrams |
| [IMPLEMENTATION_SUMMARY.md](IMPLEMENTATION_SUMMARY.md) | Technical changes made |

## 🏗️ System Architecture

```
User Browser
    ↓ floating_chat.html
    ↓ (Chat UI)
    ↓ HTTP POST /chat
        ↓
    FastAPI Backend (main.py)
        ├─ Load RAG Database (coffee_rag_vectors.json)
        ├─ Find relevant chunks (98 Uganda chunks)
        ├─ Extract metadata (country: Uganda, ministry: MAAIF)
        └─ Build context with source labels
        ↓
    OpenRouter API (Mistral 7B)
        ├─ Enhanced system prompt
        ├─ Context from guidelines
        └─ Country/ministry awareness
        ↓
    AI Response (with citations)
        ↓
    Display in Chat
```

## 📊 Knowledge Base

- **98 Knowledge Chunks** from official sources
- **Country:** Uganda
- **Ministry:** Ministry of Agriculture, Animal Industry and Fisheries (MAAIF)
- **Organization:** Uganda Coffee Development Authority (UCDA)
- **Topics:** Value chains, farming practices, cooperatives, exporters, etc.

## 🎨 Key Features

### 1. Country-Aware Responses
Every response includes Uganda's official guidelines and ministry context.

### 2. Source Attribution
Each response cites:
- Country (Uganda)
- Ministry (MAAIF)
- Organization (UCDA)
- Relevant guidelines

### 3. Enhanced Context
Responses are built from:
- Official government guidelines
- Ministry-approved content
- Organization expertise

### 4. Extensible Design
Ready to add:
- Kenya, Tanzania, Rwanda, Burundi
- Multiple organizations per country
- Cross-country comparisons

## 📝 Example Conversation

**User:** How do I improve coffee soil fertility?

**Bot:** According to Uganda's Ministry of Agriculture, Animal Industry and Fisheries (MAAIF) guidelines from the Uganda Coffee Development Authority (UCDA):

Soil fertility improvement includes:
1. Using recommended fertilizers...
2. Crop rotation practices...
3. Organic matter incorporation...

For implementation in your specific region, contact your local UCDA or MAAIF agricultural extension office.

---

## ⚙️ Environment Setup

### Required
```bash
export OPENROUTER_API_KEY="your_api_key"
```

### Optional
```bash
export OPENROUTER_MODEL="mistralai/mistral-7b-instruct:free"
export RAG_FILE="./coffee_rag_vectors.json"
```

## 📦 API Endpoints

### Chat Endpoint
```
POST /chat
Content-Type: application/json

Request:
{
  "message": "Your question about farming"
}

Response:
{
  "response": "Answer with country and ministry context..."
}
```

### Alternative Endpoints
- `POST /api/chat` - Extended format
- `POST /api/predict` - Hugging Face compatible

### Health Check
```
GET /health
```

## 🧪 Verify Your Setup

```bash
python test_system_v2.py
```

Expected output:
```
[OK] RAG data file found
[OK] Loaded 98 knowledge chunks
[OK] Metadata present
[OK] System verification complete!
```

## 🌍 Expanding to Other Countries

To add knowledge from other East African countries:

1. **Get ministerial PDFs** from country's agriculture ministry
2. **Process with pdf_to_rag.py**
3. **Enhance metadata** with country/ministry info
4. **Merge** with coffee_rag_vectors.json

See [SETUP_GUIDE.md](SETUP_GUIDE.md#extending-the-knowledge-base) for detailed instructions.

## 🚀 Deployment

### Development
```bash
python main.py
```

### Production
- **Hugging Face Spaces:** Push to GitHub, connect to Spaces
- **Docker:** `docker build -t voxenai . && docker run -p 7860:7860 voxenai`
- **Cloud:** Deploy FastAPI app to AWS/GCP/Azure

See [SETUP_GUIDE.md](SETUP_GUIDE.md#deployment-options) for each option.

## 🔒 Security

- API keys stored in environment variables
- No user data stored locally
- OpenRouter handles LLM execution
- Review OpenRouter privacy policy

## 🐛 Troubleshooting

**"API key not configured"**
→ Set OPENROUTER_API_KEY and restart

**"Could not generate response"**
→ Check internet, API key validity, OpenRouter status

**"RAG data not loading"**
→ Verify coffee_rag_vectors.json exists

More help in [QUICKSTART.md](QUICKSTART.md#troubleshooting)

## 📈 Next Steps

1. ✓ Get OpenRouter API key
2. ✓ Run `python main.py`
3. ✓ Test with sample questions
4. ✓ Customize UI in floating_chat.html
5. ✓ Add more countries (Kenya, Tanzania, etc.)
6. ✓ Deploy to production (Spaces, Docker, Cloud)

## 📞 Support Resources

- **FastAPI Docs:** https://fastapi.tiangolo.com
- **OpenRouter Docs:** https://openrouter.ai/docs
- **Hugging Face Spaces:** https://huggingface.co/spaces
- **Uganda UCDA:** https://www.ugandacoffee.go.ug

## 📄 Files Overview

```
voxenai/
├── main.py                      # Backend API (FastAPI)
├── floating_chat.html           # Frontend chat UI
├── coffee_rag_vectors.json      # Knowledge base (98 chunks)
├── requirements.txt             # Python dependencies
├── test_system_v2.py           # System verification
├── README.md                    # This file
├── QUICKSTART.md               # Quick start guide
├── SETUP_GUIDE.md              # Comprehensive guide
├── ARCHITECTURE.md             # System design
└── IMPLEMENTATION_SUMMARY.md   # Technical details
```

## ✨ Implementation Status

- [x] Knowledge base enhanced with country/ministry metadata
- [x] Backend API updated for context awareness
- [x] System prompt improved for LLM
- [x] Frontend compatible (no changes needed)
- [x] Documentation complete
- [x] Testing suite provided
- [x] Ready for production use

## 🎓 Learning More

1. **QUICKSTART.md** - For getting started immediately
2. **ARCHITECTURE.md** - To understand how it works
3. **SETUP_GUIDE.md** - For advanced configuration
4. **IMPLEMENTATION_SUMMARY.md** - For technical details

---

**Status:** Ready to Use ✓  
**Knowledge Base:** 98 Uganda chunks ✓  
**API Integration:** OpenRouter Mistral 7B ✓  
**Documentation:** Complete ✓  

**Next:** Run `python main.py` and start chatting!
