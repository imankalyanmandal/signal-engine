# Build the Java application
build:
	cd SignalEngineApplication && mvn clean compile

# Run the Java application
run-java:
	cd SignalEngineApplication && mvn spring-boot:run

# Install Python dependencies
install-python:
	cd datafetchlayers && pip install -r requirements.txt

# Run Python tests (if any)
test-python:
	cd datafetchlayers && python -m pytest

# Clean build artifacts
clean:
	cd SignalEngineApplication && mvn clean
	find datafetchlayers -type d -name __pycache__ -exec rm -rf {} +
	find datafetchlayers -name "*.pyc" -delete

# Full setup
setup: install-python build

.PHONY: build run-java install-python test-python clean setup