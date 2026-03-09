# VoxenAI Farm Assistant - Setup & Deployment Guide

## Overview

Your floating button chatbot is now enhanced to work with the LLM using a knowledge base of **East African coffee and cocoa farming guidelines** organized by countries and their respective ministries.

## Current Knowledge Base

### Uganda
- **Ministry**: Ministry of Agriculture, Animal Industry and Fisheries (MAAIF)
- **Organization**: Uganda Coffee Development Authority (UCDA)
- **Coverage**: 98 knowledge chunks covering:
  - Coffee value chain development
  - Cocoa production guidelines
  - Agro-input dealers and suppliers
  - Coffee processors and exporters
  - Supporting organizations and services
  - Farmer cooperatives and associations

## System Architecture

```
┌─────────────────────┐
│  floating_chat.html │  <- User Interface (Floating Button)
└──────────┬──────────┘
           │ HTTP POST /chat
           ▼
┌──────────────────────────────────────────┐
│        FastAPI Backend (main.py)         │
├──────────────────────────────────────────┤
│  1. Receives user message                │
│  2. Loads RAG knowledge base             │
│  3. Retrieves relevant chunks (top 3)    │
│  4. Includes country/ministry metadata   │
│  5. Sends to LLM with context            │
└─────────────┬──────────────────────────┬─┘
              │                          │
              ▼                          ▼
    ┌──────────────────┐      ┌──────────────────────┐
    │  RAG Database    │      │   OpenRouter API     │
    │  (JSON vectors)  │      │   (Mistral 7B)       │
    │  with metadata   │      │   or custom model    │
    └──────────────────┘      └──────────────────────┘
```

## Key Features

### 1. **Country & Ministry-Aware Responses**
The system now:
- Tracks which country each knowledge chunk comes from
- Includes ministry/organization information in the context
- Provides country-specific guidance
- Cites sources in responses

### 2. **Enhanced Context Building**
Each response includes:
```
[Country: Uganda | Ministry: Ministry of Agriculture, Animal Industry and Fisheries (MAAIF) | Organization: Uganda Coffee Development Authority (UCDA)]
<relevantContent>
```

### 3. **Improved LLM Prompting**
System prompt now instructs the AI to:
- Reference specific countries and ministries
- Cite guidelines from official sources
- Recommend consulting local agricultural offices when needed
- Provide East Africa-focused advice

## Installation & Setup

### Prerequisites
```
Python 3.8+
pip
virtualenv (recommended)
```

### 1. Install Dependencies
```bash
pip install -r requirements.txt
```

### 2. Set Environment Variables

**Required:**
```bash
export OPENROUTER_API_KEY="your_openrouter_api_key_here"
```

**Optional:**
```bash
export OPENROUTER_MODEL="mistralai/mistral-7b-instruct:free"
export RAG_FILE="./coffee_rag_vectors.json"
```

To get an OpenRouter API key:
1. Visit https://openrouter.ai
2. Sign up for an account
3. Create an API key from the dashboard
4. Use free models or upgrade for additional options

### 3. Run the Application

**Development:**
```bash
python main.py
# or
uvicorn main:app --reload --host 0.0.0.0 --port 7860
```

**Production (Hugging Face Spaces):**
The app automatically detects Hugging Face Spaces and runs on port 7860.

### 4. Access the Chatbot
Open your browser to: `http://localhost:7860`

Click the floating button (˙✦) in the bottom-right corner to start chatting.

## Data Files Explained

### coffee_rag_vectors.json
**Enhanced RAG database** with:
- Text content from Uganda Coffee and Cocoa guidelines
- Metadata: country, ministry, organization, crop type, topic
- Used for semantic search and context retrieval
- 98 knowledge chunks total

### uganda_coffee_cocoa_ai_chunks.json
**Source data** - Original Uganda Coffee and Cocoa Directory chunks
Used to generate coffee_rag_vectors.json

### floating_chat.html
**Frontend UI** - Embeddable floating chat widget
- Can be embedded in any website
- Communicates with `/chat`, `/api/chat`, or `/api/predict` endpoints
- Auto-retries different endpoints for compatibility

## Extending the Knowledge Base

### Adding More Countries

To add knowledge from other East African countries (Kenya, Tanzania, Rwanda, Burundi, etc.):

1. **Prepare PDF documents** from respective ministries
2. **Process with pdf_to_rag.py**:
   ```bash
   python pdf_to_rag.py path/to/pdf.pdf --country Kenya --ministry "Ministry of Agriculture"
   ```

3. **Enhance chunks** with metadata:
   ```python
   for item in data:
       item['metadata']['country'] = 'Kenya'
       item['metadata']['ministry'] = 'Ministry of Agriculture, Livestock and Fisheries'
       item['metadata']['organization'] = 'Coffee Directorate'
   ```

4. **Merge with existing** coffee_rag_vectors.json:
   ```python
   import json
   
   with open('coffee_rag_vectors.json') as f:
       existing = json.load(f)
   with open('kenya_chunks.json') as f:
       new_data = json.load(f)
   
   existing.extend(new_data)
   
   with open('coffee_rag_vectors.json', 'w') as f:
       json.dump(existing, f, indent=2)
   ```

### Metadata Fields Reference

Each knowledge chunk should have:
```json
{
  "id": 1,
  "text": "content...",
  "metadata": {
    "country": "Uganda",
    "ministry": "Ministry of Agriculture, Animal Industry and Fisheries",
    "organization": "Uganda Coffee Development Authority",
    "crop": "coffee and cocoa",
    "topic": "value chain",
    "source": "Uganda Coffee and Cocoa Directory",
    "language": "en",
    "page": 2
  }
}
```

## API Endpoints

### Chat Endpoint
```
POST /chat
Content-Type: application/json

{
  "message": "How do I improve coffee soil fertility?"
}

Response:
{
  "response": "Answer with country and ministry context..."
}
```

### Alternative Endpoints
- `POST /api/chat` - Extended format
- `POST /api/predict` - Hugging Face Spaces format

### Health Check
```
GET /health
```

## Deployment Options

### 1. Local Development
```bash
python main.py
```

### 2. Hugging Face Spaces
- Push code to a GitHub repo
- Connect to Hugging Face Spaces
- Set `OPENROUTER_API_KEY` as a Space secret
- Auto-deploys on push

### 3. Docker
```bash
docker build -t voxenai .
docker run -e OPENROUTER_API_KEY="your_key" -p 7860:7860 voxenai
```

### 4. Cloud Platforms (AWS, GCP, Azure)
- Deploy the FastAPI app using your cloud's container service
- Set environment variables via secret management tools
- Ensure port 7860 is exposed

## Customization

### Change LLM Model
Edit `main.py`:
```python
OPENROUTER_MODEL = "openai/gpt-3.5-turbo"  # or any OpenRouter model
```

### Customize System Prompt
Modify the `system_prompt` in `get_ai_response()`:
```python
system_prompt = (
    "You are a specialized agricultural advisor for East Africa. "
    "Your expertise covers coffee and cocoa farming practices. "
    # ... customize as needed
)
```

### Change Chat UI Appearance
Edit `floating_chat.html` CSS variables:
```css
:root {
  --accent: #1c9a3f;              /* Primary color */
  --accent-dark: #0e6a25;         /* Dark accent */
  --chat-bg: #ffffff;             /* Chat background */
  --text-color: #111111;          /* Text color */
}
```

## Troubleshooting

### Issue: "AI model API key not configured"
**Solution**: Set OPENROUTER_API_KEY environment variable
```bash
export OPENROUTER_API_KEY="sk-..."
```

### Issue: Chat returns "Could not generate response"
**Possible causes:**
1. OpenRouter API key invalid or expired
2. API rate limits exceeded
3. Network connectivity issues
4. Knowledge base (coffee_rag_vectors.json) not found

**Solutions:**
1. Verify API key is correct
2. Check OpenRouter account status
3. Test network connectivity
4. Ensure coffee_rag_vectors.json exists in the directory

### Issue: Responses don't include country/ministry information
**Solution**: Verify metadata is in coffee_rag_vectors.json:
```bash
python -c "import json; data = json.load(open('coffee_rag_vectors.json')); print(data[0].get('metadata'))"
```

## Performance Optimization

### For Large Knowledge Bases
1. Use sentence-transformer embeddings for better semantic search
2. Implement vector database (Chroma, Pinecone, Qdrant)
3. Cache embeddings to reduce computation

### Scaling Options
1. Use async processing with Celery
2. Implement caching layer (Redis)
3. Load balance multiple API instances
4. Use faster LLM models

## Security Considerations

1. **API Key Protection**
   - Never commit OPENROUTER_API_KEY to version control
   - Use environment variables or secret management tools
   - Rotate keys regularly

2. **Rate Limiting**
   - Implement rate limiting on /chat endpoint
   - Monitor API usage and costs

3. **Data Privacy**
   - Note that user messages are sent to OpenRouter
   - Review OpenRouter's privacy policy
   - Consider self-hosted LLM alternatives for sensitive data

## Future Enhancements

- [ ] Add support for more East African countries
- [ ] Implement multi-language support
- [ ] Add country/ministry filtering in UI
- [ ] Create admin dashboard for knowledge base management
- [ ] Add feedback mechanism for continuous improvement
- [ ] Implement caching for frequently asked questions
- [ ] Add export/report generation features
- [ ] Support for image/document uploads

## Support & Resources

- **OpenRouter Docs**: https://openrouter.ai/docs
- **FastAPI Docs**: https://fastapi.tiangolo.com
- **Hugging Face Spaces**: https://huggingface.co/spaces
- **Uganda UCDA**: https://www.ugandacoffee.go.ug

---

**Version**: 1.0
**Last Updated**: March 2025
**Author**: VoxenAI Team
