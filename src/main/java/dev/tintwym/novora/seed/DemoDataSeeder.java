package dev.tintwym.novora.seed;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import dev.tintwym.novora.domain.entity.TenantCompany;
import dev.tintwym.novora.domain.entity.UserEntity;
import dev.tintwym.novora.domain.enums.Role;
import dev.tintwym.novora.repositories.TenantCompanyRepository;
import dev.tintwym.novora.repositories.UserEntityRepository;

@Component
@ConditionalOnProperty(name = "novora.seed-demo-admin", havingValue = "true")
public class DemoDataSeeder implements CommandLineRunner {

	private final UserEntityRepository users;
	private final TenantCompanyRepository companies;
	private final PasswordEncoder passwordEncoder;

	public DemoDataSeeder(UserEntityRepository users, TenantCompanyRepository companies,
			PasswordEncoder passwordEncoder) {
		this.users = users;
		this.companies = companies;
		this.passwordEncoder = passwordEncoder;
	}

	@Override
	public void run(String... args) {
		String email = "admin@novora.local";
		if (users.findByEmailIgnoreCase(email).isPresent()) {
			return;
		}
		TenantCompany company = new TenantCompany();
		company.setName("Novora Demo Co");
		company.setIndustry("Technology");
		company.setSize("11-50");
		company.setPortalSlug("novora-demo");
		companies.save(company);

		UserEntity admin = new UserEntity();
		admin.setName("Demo Admin");
		admin.setEmail(email);
		admin.setPasswordHash(passwordEncoder.encode("password123"));
		admin.setRole(Role.ADMIN);
		admin.setCompanyId(company.getId());
		users.save(admin);
	}
}
