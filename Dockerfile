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