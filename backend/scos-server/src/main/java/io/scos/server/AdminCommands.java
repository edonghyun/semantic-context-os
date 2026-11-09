package io.scos.server;

import io.scos.engine.admin.AdminBootstrap;
import java.nio.file.Path;

/** bootstrap 명령 실행과 고정 안내만 소유한다. 원문·예외 객체는 출력하지 않는다. */
final class AdminCommands {
  private static final String RECOVERY =
      "bootstrap: commit outcome requires verification; retain the protected token file and verify database state before retrying.";
  private final AdminBootstrap bootstrap;
  private final CommandOutput errors;

  AdminCommands(AdminBootstrap bootstrap, CommandOutput errors) {
    this.bootstrap = bootstrap;
    this.errors = errors;
  }

  int run(CommandArguments args) {
    BootstrapTokenFile file;
    String email;
    try {
      args.requireBootstrapOptions();
      email = args.required("email");
      file = new BootstrapTokenFile(Path.of(args.required("token-file")));
    } catch (IllegalArgumentException invalid) {
      errors.write("usage: admin bootstrap --email <email> --token-file <path>");
      return 2;
    }
    return execute(email, file);
  }

  int execute(String email, BootstrapTokenFile file) {
    try {
      file.verifyDestination();
      bootstrap.bootstrap(email, file);
      return 0;
    } catch (AdminBootstrap.Failure failure) {
      if (failure.recoveryFileRequired()) {
        errors.write(RECOVERY);
        return 1;
      }
      cleanup(file);
      errors.write("bootstrap: " + failure.getMessage());
      return "invalid_request".equals(failure.getMessage()) ? 2 : 1;
    } catch (RuntimeException failure) {
      cleanup(file);
      errors.write("bootstrap: failed to prepare administrator and token file");
      return 1;
    } finally {
      release(file);
    }
  }

  private void release(BootstrapTokenFile file) {
    try {
      file.close();
    } catch (RuntimeException failure) {
      errors.write(file.recoveryNotice());
    }
  }

  private void cleanup(BootstrapTokenFile file) {
    try {
      file.remove();
    } catch (RuntimeException failure) {
      errors.write(file.recoveryNotice());
    }
  }
}
