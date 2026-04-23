# Sorting Helper

A comprehensive financial signal analysis and backtesting platform consisting of Python data fetching layers and a Java-based signal engine application.

## Project Structure

### Data Fetch Layers (`datafetchlayers/`)
Python modules for fetching and analyzing financial data:

- `fundamentals_fetcher.py` - Fetches fundamental financial data
- `market_service.py` - Market data services
- `sentiment_analyser.py` - Sentiment analysis for market data
- `concall_analyser.py` - Conference call analysis
- `composite_scorer.py` - Composite scoring algorithms
- `symbol_provider.py` - Stock symbol management
- `llm_client.py` - Large Language Model integration
- `claude_config.py` - Claude AI configuration
- `mock_responses.py` - Mock data for testing

### Signal Engine Application (`SignalEngineApplication/`)
Java Spring Boot application for signal processing and backtesting:

#### Core Components:
- **Controllers**: REST API endpoints (`BacktestController.java`)
- **Services**:
  - `BacktestService.java` - Backtesting logic
  - `Layer2Service.java` - Layer 2 data processing
  - `MarketDataClient.java` - Market data client
  - `Nifty50ScannerService.java` - Nifty 50 stock scanning
  - `StockDataService.java` - Stock data management
- **Indicators**: Technical indicators (`ATRIndicator.java`, `BollingerBandsIndicator.java`, `RSIIndicator.java`, `SMAIndicator.java`)
- **Models**: Data models (`BacktestConfig.java`, `BacktestResult.java`, `Candle.java`, etc.)
- **Strategies**: Trading strategy interfaces and implementations

#### Sample Data:
- `HDFCBANK.csv`, `INFY.csv`, `RELIANCE.csv`, `TCS.csv` - Historical stock data

## Project Files

### Configuration Files
- `.gitignore` - Git ignore patterns for Python, Java, and common files
- `Makefile` - Common build and run commands
- `datafetchlayers/requirements.txt` - Python dependencies
- `datafetchlayers/.env.example` - Environment variables template

### Java Application
- `SignalEngineApplication/pom.xml` - Maven configuration with Spring Boot dependencies

## Prerequisites

### Python Environment
- Python 3.8+
- Required packages listed in `datafetchlayers/requirements.txt`

### Java Environment
- Java 17+
- Maven 3.6+

## Setup and Installation

### Quick Setup
Use the provided Makefile for easy setup:

```bash
make setup
```

This will install Python dependencies and build the Java application.

### Manual Setup

#### Python Data Layers
1. Navigate to the datafetchlayers directory:
   ```bash
   cd datafetchlayers
   ```

2. Install dependencies:
   ```bash
   pip install -r requirements.txt
   ```

3. Copy environment configuration:
   ```bash
   cp .env.example .env
   ```
   Edit `.env` with your API keys and configuration.

#### Java Signal Engine
1. Navigate to the SignalEngineApplication directory:
   ```bash
   cd SignalEngineApplication
   ```

2. Build the application:
   ```bash
   mvn clean install
   ```

3. Run the application:
   ```bash
   mvn spring-boot:run
   ```

## Setup and Installation

### Python Data Layers
1. Navigate to the datafetchlayers directory:
   ```bash
   cd datafetchlayers
   ```

2. Create a virtual environment:
   ```bash
   python -m venv venv
   source venv/bin/activate  # On Windows: venv\Scripts\activate
   ```

3. Install dependencies (if requirements.txt exists):
   ```bash
   pip install -r requirements.txt
   ```

### Java Signal Engine
1. Navigate to the SignalEngineApplication directory:
   ```bash
   cd SignalEngineApplication
   ```

2. Build the application:
   ```bash
   mvn clean install
   ```

3. Run the application:
   ```bash
   mvn spring-boot:run
   ```

## Usage

### Running the Applications

#### Using Makefile
```bash
# Run Java application
make run-java

# Install Python dependencies
make install-python

# Clean all artifacts
make clean
```

#### Manual Running

##### Java Signal Engine
The Java application provides REST endpoints for:
- Backtesting trading strategies
- Scanning Nifty 50 stocks
- Processing market data

Run with:
```bash
cd SignalEngineApplication
mvn spring-boot:run
```

##### Python Data Fetching
Use the Python modules to fetch and analyze financial data from various sources.

### Environment Configuration
- Copy `datafetchlayers/.env.example` to `datafetchlayers/.env`
- Add your API keys for LLM services (Gemini, Groq)
- Configure Ollama settings if using local LLM

## Testing

### Java Tests
Run unit tests:
```bash
cd SignalEngineApplication
mvn test
```

### Python Tests
Run Python tests (if available):
```bash
cd datafetchlayers
python -m pytest
```

## Configuration

- Java application properties: `SignalEngineApplication/src/main/resources/application.properties`
- Python configurations: Check individual Python files for configuration options

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests if applicable
5. Submit a pull request

## License

[Add license information here]
