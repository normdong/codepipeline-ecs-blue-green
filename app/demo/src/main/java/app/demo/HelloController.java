package app.demo;

import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.springframework.web.bind.annotation.RequestMapping;

@RestController
public class HelloController {

	@RequestMapping("/")
	public String index() {
        String response;
        try {
            InetAddress addr = InetAddress.getLocalHost();
            response = 
                "<html>" +
                "Hostname: " + addr.getHostName() + "<br>" +
                "IP Address: " + addr.getHostAddress() +
                "</html>";
        } catch (UnknownHostException e) {
            response = "Unable to get host address information :(";
        }
		return response;
	}

}