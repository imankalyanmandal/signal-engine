package signal_engine.common.management.SignalEngineApplication;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class SignalEngineApplication {

	public static void main(String[] args) {
		SpringApplication.run(SignalEngineApplication.class, args);
	}

}
