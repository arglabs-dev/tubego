# Base Image
FROM python:3.10-slim

# Set working directory
WORKDIR /app

# Install system dependencies
# ffmpeg: required for merging video/audio
# git: required for /update command
RUN apt-get update && apt-get install -y \
    ffmpeg \
    git \
    && rm -rf /var/lib/apt/lists/*

# Install Python dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy project files
COPY . .

# Create directories for persistence
RUN mkdir -p logs data downloads

# Set environment variables
# Ensure Python outputs everything to console immediately
# Ensure imports work from the root /app directory
ENV PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app

# Command to run the bot
CMD ["python", "src/bot.py"]
