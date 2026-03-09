# Quick Start Guide - VOXENAI Farm Assistant

## What Was Enhanced

Your floating button chatbot now has **country and ministry-aware** abilities for East African coffee and cocoa farming guidelines.

### New Capabilities

1. **Country & Ministry Awareness**
   - Responses include Uganda's Ministry of Agriculture guidance
   - Metadata shows the source organization (UCDA)
   - Contextual answers tied to specific guidelines

2. **Enhanced Knowledge Base**
   - 98 structured knowledge chunks
   - Each chunk tagged with country, ministry, and organization
   - Topics: value chains, farming practices, cooperatives, exporters

3. **Improved System Prompt**
   - LLM now asks to cite countries and ministries
   - Recommends consulting local agricultural offices
   - Provides East Africa-specific guidance

## 🚀 Getting Started in 2 Minutes

### Step 1: Set Your API Key
```bash
# Windows PowerShell
$env:OPENROUTER_API_KEY="your_key_here"

# Or set it permanently in your system environment variables
# Control Panel > System > Advanced > Environment Variables
```

Get a free API key from: https://openrouter.ai/keys

### Step 2: Run the App
```bash
python main.py
```

You'll see:
```
INFO:     Uvicorn running on http://0.0.0.0:7860
```

### Step 3: Use the Chatbot
1. Open browser to http://localhost:7860
2. Click the floating button in bottom-right (˙✦)
3. Ask a question like:
   - "How do I improve coffee soil fertility?"
   - "What are the guidelines for cocoa production?"
   - "Where can I find coffee processors in Uganda?"

## Example Conversations

### Before Enhancement
**Q:** How do I improve coffee soil fertility?  
**A:** [Generic answer without country context]

### After Enhancement
**Q:** How do I improve coffee soil fertility?  
**A:** According to Uganda's Ministry of Agriculture, Animal Industry and Fisheries (MAAIF) guidelines from the Uganda Coffee Development Authority (UCDA)... [Country-specific guidance]

## File Changes Made

### Modified Files:
- **main.py** - Enhanced with:
  - Country/ministry metadata tracking
  - Improved system prompt for LLM
  - Better context building with source information
  
- **coffee_rag_vectors.json** - Enhanced with:
  - Country: Uganda
  - Ministry: Ministry of Agriculture, Animal Industry and Fisheries (MAAIF)
  - Organization: Uganda Coffee Development Authority (UCDA)

### New Files Created:
- **SETUP_GUIDE.md** - Comprehensive setup and deployment guide
- **test_system_v2.py** - System verification test script

## System Architecture

```
User clicks floating button
            |
            v
    floating_chat.html
            |
            v (HTTP POST /chat)
    FastAPI Backend (main.py)
            |
     /----------\
     |          |
     v          v
  RAG DB     OpenRouter API
 (Uganda     (Mistral 7B)
 Chunks)
     |          |
     \----------/
            |
            v
    Response with country/ministry context
            |
            v
    Displayed in chat
```

## Testing Your Setup

Run the included test:
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

## Common Questions

**Q: Do I need to be online?**
A: Yes, the app requires internet to connect to OpenRouter API for LLM responses.

**Q: Is my data sent anywhere?**
A: User messages are sent to OpenRouter (Mistral model). Review their privacy policy: https://openrouter.ai

**Q: Can I add more countries?**
A: Yes! See SETUP_GUIDE.md section "Extending the Knowledge Base"

**Q: What if I get "API key not configured"?**
A: Make sure you've set OPENROUTER_API_KEY environment variable and restarted the app.

## Troubleshooting

### Chat says "AI model API key not configured"
- Verify OPENROUTER_API_KEY is set: `echo $env:OPENROUTER_API_KEY`
- Restart the application after setting the key

### "Could not generate response"
- Check your OpenRouter account status
- Verify internet connectivity
- Check that your API key is valid

### RAG data not loading
- Verify coffee_rag_vectors.json exists in the workspace
- Check file is valid JSON: `python -m json.tool coffee_rag_vectors.json`

## Next Steps

1. **Customize the UI** - Edit floating_chat.html to match your branding
2. **Add More Countries** - Process PDFs from Kenya, Tanzania, Rwanda, etc.
3. **Deploy to Production** - Use Hugging Face Spaces, Docker, or cloud providers
4. **Monitor Usage** - Track API calls and user interactions

## Additional Resources

- [FastAPI Documentation](https://fastapi.tiangolo.com/)
- [OpenRouter Models](https://openrouter.ai/docs)
- [Uganda UCDA](https://www.ugandacoffee.go.ug/)
- [SETUP_GUIDE.md](./SETUP_GUIDE.md) - Full setup guide

## Support

For issues or enhancements:
1. Check logs in terminal output
2. Review SETUP_GUIDE.md troubleshooting section
3. Verify all files are in correct locations
4. Test with test_system_v2.py

---

**You're all set!** Your floating chatbot now speaks fluent East African agricultural guidelines.

Start with: `python main.py`
