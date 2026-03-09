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

# Set the API key as environment variable
ENV OPENROUTER_API_KEY="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"

# Start FastAPI with uvicorn
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "7860"]