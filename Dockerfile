# Use an official Python base image
FROM python:3.10-slim

# Set working directory
WORKDIR /app

# Copy requirements and install dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy all files into the container
COPY . .

# Expose the port Hugging Face expects
EXPOSE 7860

# Set the Groq API key as environment variable (set via cPanel or deployment config)
# ENV GROQ_API_KEY="your-key-here"

# Start FastAPI with uvicorn
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "7860"]