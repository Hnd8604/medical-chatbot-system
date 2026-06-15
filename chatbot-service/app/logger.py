import logging
import sys
import re

class SensitiveDataFormatter(logging.Formatter):
    """
    Formatter tùy chỉnh giúp che giấu thông tin nhạy cảm (PII - Email, SĐT, ID) 
    trước khi in ra log (Module M11.4).
    """
    
    PHONE_REGEX = re.compile(r'\b(\+?\d{2,3})\d{4,10}(\d{2})\b')
    
    ID_REGEX = re.compile(r'\b(\d{3})\d{3,6}(\d{3})\b')
    
    EMAIL_REGEX = re.compile(r'\b([a-zA-Z0-9]{1,3})[a-zA-Z0-9._%+-]*(@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,})\b')

    def format(self, record):
        message = super().format(record)
        
        message = self.EMAIL_REGEX.sub(r'\1***\2', message)
        
        message = self.ID_REGEX.sub(r'\1******\2', message)
        
        message = self.PHONE_REGEX.sub(r'\1****\2', message)
        
        return message

def setup_logging():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')

    console_handler = logging.StreamHandler(sys.stdout)
    
    formatter = SensitiveDataFormatter("%(asctime)s [%(levelname)s] %(name)s - %(message)s")
    console_handler.setFormatter(formatter)

    logging.basicConfig(
        level=logging.INFO,
        handlers=[console_handler],
        force=True 
    )